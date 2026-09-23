package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
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
    fun `repair can return the same optimum under an unchanged cutoff`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        solver.openRepair(objective, BacktrackParams()).use { repair ->
            repeat(3) {
                val sample = assertNotNull(repair.repair(Assumptions.None, 100L, 0.0))
                assertEquals(-1.0, objective.evaluate(sample))
            }
        }
    }

    @Test
    fun `repair excludes an optimum at the tightened cutoff`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        solver.openRepair(objective, BacktrackParams()).use { repair ->
            assertEquals(-1.0, objective.evaluate(assertNotNull(repair.repair(Assumptions.None, 100L, 0.0))))
            assertNull(repair.repair(Assumptions.None, 100L, -1.0))
        }
    }

    @Test
    fun `repair rejects an increasing cutoff`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        solver.openRepair(objective, BacktrackParams()).use { repair ->
            assertNotNull(repair.repair(Assumptions.None, 100L, 0.0))
            assertFailsWith<IllegalArgumentException> {
                repair.repair(Assumptions.None, 100L, 1.0)
            }
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
            val full = assertNotNull(repair.repair(Assumptions.None, 1000L, 0.0))
            assertEquals(-4.0, objective.evaluate(full))

            val restricted = assertNotNull(
                repair.repair(Assumptions(mapOf(0 to false), emptyMap()), 1000L, 0.0),
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
        val a = handle.repair(Assumptions(mapOf(2 to false, 3 to false), emptyMap()), 2_000L, Double.POSITIVE_INFINITY)
        assertNotNull(a)
        assertEquals(5.0, objective.evaluate(a))

        // Reuse the SAME handle with a different pin set → {2,3}; the cheaper choice is var 3 (weight 3).
        val b = handle.repair(Assumptions(mapOf(0 to false, 1 to false), emptyMap()), 2_000L, Double.POSITIVE_INFINITY)
        assertNotNull(b)
        assertEquals(3.0, objective.evaluate(b), "the re-seeded fragment solves correctly, uncorrupted by the first")

        handle.close()
    }
}
