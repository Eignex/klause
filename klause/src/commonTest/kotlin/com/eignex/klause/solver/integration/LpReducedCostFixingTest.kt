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

/** #21: LP reduced-cost fixing wired into BacktrackSolver branch-and-bound. */
class LpReducedCostFixingTest {

    // min x0 + 10·x1 s.t. x0 + x1 >= 5, both in [0,10]. Optimum 5 at (5,0). With the constraint
    // active the objective is 5 + 9·x1, so x1's reduced cost is 9: under a tight incumbent it is
    // exactly the variable reduced-cost fixing should pin down.
    private fun weighted(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 5)),
    )

    private val obj = LinearObjective(intCoefficients = longArrayOf(1L, 10L))

    @Test
    fun `reduced-cost fixing preserves the optimum`() {
        val problem = weighted()
        val off = BacktrackSolver(problem).minimize(obj, BacktrackParams(randomSeed = 1L))
        val on = BacktrackSolver(
            problem,
        ).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)))

        assertEquals(off.objectiveValue, on.objectiveValue, "fixing must not change the optimum")
        assertEquals(5.0, on.objectiveValue)
    }

    @Test
    fun `reduced-cost fixing fires under a tight external bound`() {
        // A generous external incumbent of 6 makes the gap known from the first node, so the LP's
        // reduced cost on x1 (= 9) immediately bounds x1 in. The optimum is still 5.
        val problem = weighted()
        val result = BacktrackSolver(problem).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, objectiveBoundSupplier = { 6.0 }, lpPlan = LpPlan(bounding = true)),
        )
        assertTrue(result.objectiveValue == 5.0, "optimum should still be reached, got ${result.objectiveValue}")
        assertTrue(result.stats.lp.fixed.sum > 0.0, "expected reduced-cost fixings, got ${result.stats.lp.fixed.sum}")
    }

    @Test
    fun `fixing stays sound on the triangle covering problem`() {
        // Same instance #20 used; reduced-cost fixing on top must still prove optimum 3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
            ),
        )
        val triObj = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
        val result = BacktrackSolver(
            problem,
        ).minimize(triObj, BacktrackParams(randomSeed = 4L, lpPlan = LpPlan(bounding = true)))
        assertTrue(result is MinimizeResult.Optimal)
        assertEquals(3.0, result.objectiveValue)
    }
}
