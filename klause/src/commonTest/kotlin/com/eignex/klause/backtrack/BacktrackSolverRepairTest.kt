package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BacktrackSolverRepairTest {
    @Test
    fun `repair should leave an unconsumed improving ray unavailable`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY)).bake()
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        BacktrackSolver(model).openRepair(objective, BacktrackParams()).use { repair ->
            val step = repair.repairStep(Assumptions.None, 100L, -1.0, Cancellation.Never)

            assertEquals(TerminationReason.Unsupported, step.reason)
        }
    }

    @Test
    fun `repair should resume the same fragment after a cancelled slice`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L, -2L))
        val solver = BacktrackSolver(Problem(2, 0, emptyArray(), emptyArray()).bake())
        solver.openRepair(objective, BacktrackParams()).use { repair ->
            repair.begin(Assumptions.None, 100L, 0.0, Cancellation.Never)

            val paused = repair.runSlice(Cancellation { true }, Long.MAX_VALUE, 1L)
            var completed = paused
            repeat(32) {
                if (completed.reason == null) completed = repair.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L)
            }

            assertNull(paused.reason)
            assertEquals(TerminationReason.SearchExhausted, completed.reason)
            assertEquals(-3.0, objective.evaluate(assertNotNull(completed.sample)))
        }
    }

    @Test
    fun `repair should keep lifetime cancellation terminal until another fragment begins`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())
        var cancelled = false
        solver.openRepair(objective, BacktrackParams()).use { repair ->
            repair.begin(Assumptions.None, 100L, 0.0, Cancellation { cancelled })
            cancelled = true

            val stopped = repair.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)
            cancelled = false
            val retained = repair.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L)
            val next = repair.repair(Assumptions.None, 100L, 0.0, Cancellation.Never)

            assertEquals(TerminationReason.Cancelled, stopped.reason)
            assertEquals(stopped, retained)
            assertEquals(-1.0, objective.evaluate(assertNotNull(next)))
        }
    }

    @Test
    fun `repair should reset an expired slice before reseeding a sibling scope`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L, -2L))
        val solver = BacktrackSolver(Problem(2, 0, emptyArray(), emptyArray()).bake())
        solver.openRepair(objective, BacktrackParams()).use { repair ->
            repair.begin(Assumptions(mapOf(0 to false), emptyMap()), 100L, 0.0, Cancellation.Never)
            repair.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L)

            val next = repair.repair(Assumptions(mapOf(1 to false), emptyMap()), 100L, 0.0, Cancellation.Never)

            assertEquals(true, assertNotNull(next).boolValue(0))
            assertEquals(false, next.boolValue(1))
        }
    }

    @Test
    fun `repair should reject an increasing or NaN cutoff`() {
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
    fun `repair should compare wide discrete objectives to the cutoff exactly`() {
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
    fun `repair should publish a second exact improvement with the same displayed score`() {
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
    fun `repair should reuse one session across pin sets and return each optimum`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val handle = BacktrackSolver(problem.bake()).openRepair(objective, BacktrackParams())

        val a = handle.repair(
            Assumptions(mapOf(2 to false, 3 to false), emptyMap()),
            2_000L,
            Double.POSITIVE_INFINITY,
            Cancellation.Never,
        )
        assertNotNull(a)
        assertEquals(5.0, objective.evaluate(a))

        val b = handle.repair(
            Assumptions(mapOf(0 to false, 1 to false), emptyMap()),
            2_000L,
            Double.POSITIVE_INFINITY,
            Cancellation.Never,
        )
        assertNotNull(b)
        assertEquals(3.0, objective.evaluate(b))

        handle.close()
    }
}
