package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The persistent LNS repair handle ([BacktrackSolver.openRepair], #644): one search re-seeded across
 * successive pin sets must stay correct — reusing the session (learned DB, LP) between fragments must not
 * let one repair's state corrupt the next.
 */
class RepairSearchTest {

    @Test
    fun `repair reuses one session across pin sets and returns the correct optimum each time`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val handle = BacktrackSolver(problem).openRepair(objective, BacktrackParams())

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
