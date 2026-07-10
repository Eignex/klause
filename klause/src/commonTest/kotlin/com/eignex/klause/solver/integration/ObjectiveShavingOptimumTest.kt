package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.lp.LpPlan
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Objective shaving must not change the solved optimum end to end, and the proven objective floor must
 * reach the shared lower-bound sink for peer portfolio arms.
 */
class ObjectiveShavingOptimumTest {

    /** `cost = x0+x1+x2` over {0,1}³ with the three pair-covering rows (triangle vertex cover): every
     *  solution needs ≥ 2 ones, so the minimum `cost` is 2 — but its declared domain min is 0. */
    private fun triangleCover(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4, // x0, x1, x2, cost
        intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1, 1, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0), // cost channelling
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        ),
    )

    @Test
    fun `shaving preserves the optimum end to end`() {
        val p = triangleCover()
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val off = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)))
        val on = BacktrackSolver(p).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, objectiveShaving = true)),
        )
        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(2.0, off.objectiveValue)
        assertEquals(2.0, on.objectiveValue, "objective shaving changed the optimum")
    }

    @Test
    fun `the proven objective floor reaches the lower-bound sink`() {
        // The shaved floor (cost >= 2) is a global lower bound; it must be published to the portfolio's
        // shared lower-bound sink so a peer arm can pick it up.
        val p = triangleCover()
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val published = ArrayList<Double>()
        val result = BacktrackSolver(p).minimize(
            obj,
            BacktrackParams(
                randomSeed = 1L,
                lpPlan = LpPlan(bounding = true, objectiveShaving = true),
                objectiveLowerBoundSink = { published.add(it) },
            ),
        )
        assertTrue(result is MinimizeResult.Optimal && result.objectiveValue == 2.0, "optimum is 2")
        assertTrue(published.any { it >= 2.0 }, "the proven floor (cost >= 2) must reach the sink, got $published")
    }
}
