package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The persistent LNS repair handle ([BacktrackSolver.openRepair], #644): one search re-seeded across
 * successive pin sets must stay correct — reusing the session (learned DB, LP) between fragments must not
 * let one repair's state corrupt the next.
 */
class RepairSearchTest {

    @Test
    fun `repair rejects an increasing or NaN cutoff`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        for (invalid in listOf(1.0, Double.NaN)) {
            solver.openRepair(objective, BacktrackParams()).use { repair ->
                assertNotNull(repair.repair(Assumptions.None, 100L, 0.0, Cancellation.Never))
                assertFailsWith<IllegalArgumentException> {
                    repair.repair(Assumptions.None, 100L, invalid, Cancellation.Never)
                }
            }
        }
    }

    @Test
    fun `repair compares wide discrete objectives to the cutoff exactly`() {
        val cutoff = -9_007_199_254_740_992.0
        val cutoffAsLong = -9_007_199_254_740_992L
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        for (weight in listOf(-9_007_199_254_740_993L, cutoffAsLong)) {
            val objective = LinearObjective(boolWeights = longArrayOf(weight))
            val improvingSourceValues = listOf(0L, weight).filter { it < cutoffAsLong }
            solver.openRepair(objective, BacktrackParams()).use { repair ->
                val sample = repair.repair(Assumptions.None, 100L, cutoff, Cancellation.Never)
                if (improvingSourceValues.isEmpty()) {
                    assertNull(sample)
                } else {
                    assertEquals(true, assertNotNull(sample).bools.single())
                    assertEquals(improvingSourceValues.single(), objective.evaluateLong(sample))
                }
            }
        }
    }

    @Test
    fun `repair publishes a second exact improvement with the same displayed score`() {
        val values = listOf(-9_007_199_254_740_992L, -9_007_199_254_740_993L)
        val problem = Problem(0, 1, arrayOf(IntDomain(values.min(), values.max())), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val offers = ArrayList<Long>()
        val params = BacktrackParams(
            valueSelector = IndomainMax,
            improvedSolutionSink = { sample, _ -> offers += sample.ints.single() },
        )

        BacktrackSolver(problem).openRepair(objective, params).use { repair ->
            val best = assertNotNull(
                repair.repair(Assumptions.None, 100L, Double.POSITIVE_INFINITY, Cancellation.Never),
            )
            assertEquals(values.min(), best.ints.single())
            assertEquals(values, offers)
        }
    }

    @Test
    fun `repair reuses one session across pin sets and returns the correct optimum each time`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val handle = BacktrackSolver(problem.bake()).openRepair(objective, BacktrackParams())

        // Pin 2,3 false → exactly-one over {0,1}; the cheaper choice is var 1 (weight 5).
        val a = handle.repair(
            Assumptions(mapOf(2 to false, 3 to false), emptyMap()),
            2_000L,
            Double.POSITIVE_INFINITY,
            Cancellation.Never,
        )
        assertNotNull(a)
        assertEquals(5.0, objective.evaluate(a))

        // Reuse the SAME handle with a different pin set → {2,3}; the cheaper choice is var 3 (weight 3).
        val b = handle.repair(
            Assumptions(mapOf(0 to false, 1 to false), emptyMap()),
            2_000L,
            Double.POSITIVE_INFINITY,
            Cancellation.Never,
        )
        assertNotNull(b)
        assertEquals(3.0, objective.evaluate(b), "the re-seeded fragment solves correctly, uncorrupted by the first")

        handle.close()
    }
}
