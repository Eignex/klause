package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #246: LP-guided value ordering (round-toward-LP diving) and the rounding probe must not shift the optimum. */
class LpBranchingOptimumTest {

    @Test
    fun `lp branching preserves the optimum`() {
        // Triangle covering: x_i+x_j >= 2 over [0,5], minimize sum -> 3. Diving must not change it.
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 5) },
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val off = BacktrackSolver(
            p.bake(),
        ).minimize(obj, BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true)))
        val on = BacktrackSolver(p.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, branching = true)),
        )
        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(3.0, off.objectiveValue)
        assertEquals(3.0, on.objectiveValue)
    }

    @Test
    fun `lp rounding probe preserves the optimum`() {
        // Same covering problem; the probe seeds an incumbent before search but must not bias the optimum.
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 5) },
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val on = BacktrackSolver(p.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, probe = true)),
        )
        assertTrue(on is MinimizeResult.Optimal)
        assertEquals(3.0, on.objectiveValue)
    }
}
