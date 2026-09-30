package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The persistent LNS repair handle ([BacktrackSolver.openRepair], #644): one search re-seeded across
 * successive pin sets must stay correct — reusing the session (learned DB, LP) between fragments must not
 * let one repair's state corrupt the next.
 */
class RepairSearchTest {

    @Test
    fun `repair can return the same optimum under an unchanged cutoff`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        solver.openRepair(objective, BacktrackParams()).use { repair ->
            repeat(3) {
                val sample = assertNotNull(repair.repair(Assumptions.None, 100L, 0.0, Cancellation.Never))
                assertEquals(-1.0, objective.evaluate(sample))
            }
        }
    }

    @Test
    fun `repair excludes an optimum at the tightened cutoff`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        solver.openRepair(objective, BacktrackParams()).use { repair ->
            val best = assertNotNull(repair.repair(Assumptions.None, 100L, 0.0, Cancellation.Never))
            assertEquals(-1.0, objective.evaluate(best))
            assertNull(repair.repair(Assumptions.None, 100L, -1.0, Cancellation.Never))
        }
    }

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
    fun `repair respects a fractional cutoff`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-2L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        for (cutoff in listOf(-1.5, -2.0)) {
            val improvingSourceValues = listOf(0L, -2L).filter { it.toDouble() < cutoff }
            solver.openRepair(objective, BacktrackParams()).use { repair ->
                val sample = repair.repair(Assumptions.None, 100L, cutoff, Cancellation.Never)
                if (improvingSourceValues.isEmpty()) {
                    assertNull(sample)
                } else {
                    assertEquals(improvingSourceValues.single(), objective.evaluateLong(assertNotNull(sample)))
                }
            }
        }
    }

    @Test
    fun `repair rejects an overflowing discrete score above the cutoff`() {
        val objective = LinearObjective(boolWeights = longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE))
        val solver = BacktrackSolver(Problem(2, 0, emptyArray(), emptyArray()).bake())
        val forced = Assumptions(mapOf(0 to true, 1 to true), emptyMap())
        val sourceCost = BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ofLong(Long.MAX_VALUE)
        assertTrue(sourceCost > BigFraction.ofLong(1L))

        solver.openRepair(objective, BacktrackParams()).use { repair ->
            assertNull(repair.repair(forced, 100L, 1.0, Cancellation.Never))
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
    fun `repair retains finite cutoff LP fixing and finds a worse restricted optimum`() {
        val fixture = FiniteCutoffKnapsackFixture
        var observedSession: PropagationSession? = null
        val exchange = object : ClauseExchange {
            override fun onRestart(session: PropagationSession) = Unit
            override fun onSearchStart(session: PropagationSession) {
                observedSession = session
            }

            override fun publishGlobal(clause: SharedClause) = Unit
        }
        val params = fixture.params.copy(clauseExchange = exchange)

        BacktrackSolver(fixture.problem).openRepair(fixture.objective, params).use { repair ->
            val initial = assertNotNull(repair.repair(Assumptions.None, 100000L, 4.0, Cancellation.Never))
            val initialMask = fixture.weights.indices.sumOf { initial.ints[it].toInt() shl it }
            assertTrue(initialMask in fixture.improvingMasks(4L))
            assertEquals(-4L, fixture.sourceCost(initialMask))
            assertEquals(-4L, fixture.objective.evaluateLong(initial))
            assertEquals(1L, assertNotNull(observedSession).intDomain(1).min)

            val restricted = assertNotNull(
                repair.repair(Assumptions.None.withInt(0, 0), 100000L, 4.0, Cancellation.Never),
            )
            val restrictedMask = fixture.weights.indices.sumOf { restricted.ints[it].toInt() shl it }
            assertTrue(restrictedMask in fixture.improvingMasks(4L, 0L))
            assertEquals(3L, fixture.sourceCost(restrictedMask))
            assertEquals(3L, fixture.objective.evaluateLong(restricted))
            assertEquals(1L, assertNotNull(observedSession).intDomain(1).min)

            assertNull(repair.repair(Assumptions.None.withInt(0, 0), 100000L, 3.0, Cancellation.Never))
            assertTrue(fixture.improvingMasks(3L, 0L).isEmpty())

            val improved = assertNotNull(
                repair.repair(Assumptions.None.withInt(0, 1), 100000L, 2.0, Cancellation.Never),
            )
            assertEquals(-4L, fixture.objective.evaluateLong(improved))
            assertEquals(-4L, fixture.improvingMasks(2L, 1L).minOf(fixture::sourceCost))

            assertNull(repair.repair(Assumptions.None, 100000L, -4.0, Cancellation.Never))
            assertTrue(fixture.improvingMasks(-4L).isEmpty())
        }
    }

    @Test
    fun `repair can find a worse restricted optimum under an unchanged cutoff`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(-4L, -3L, -2L))
        val solver = BacktrackSolver(Problem(3, 0, emptyArray(), listOf(factor)).bake())
        val params = BacktrackParams(pbObjectiveCutoff = true, lubyRestartBase = 1)

        solver.openRepair(objective, params).use { repair ->
            val full = assertNotNull(repair.repair(Assumptions.None, 1000L, 0.0, Cancellation.Never))
            assertEquals(-4.0, objective.evaluate(full))

            val restricted = assertNotNull(
                repair.repair(Assumptions(mapOf(0 to false), emptyMap()), 1000L, 0.0, Cancellation.Never),
            )
            assertEquals(false, restricted.bools[0])
            assertEquals(-3.0, objective.evaluate(restricted))
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
