package com.eignex.klause.backtrack

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.OptimalityGap
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResumableMinimizeGapTest : RuntimeInitializedTest() {
    @Test
    fun `gap exhaustion should retain the weakest proved bound across pruned regions`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(10, 12, 14, 0))
        val gap = OptimalityGap(OptimalityTolerance(SemanticRational.of(bigIntOf(17))), integral = true)
        val params = BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMax,
            lpPlan = LpPlan(bounding = true, learn = true), optimalityGap = gap)

        for (numerical in listOf(false, true)) {
            val context = if (numerical) LpSolveContext(numericalDeductions = true,
                certificationPolicy = LpCertificationPolicy { _, _ -> false }) else LpSolveContext.Production

            val result = ResumableMinimize(BacktrackSolver(problem, context), objective, params, pausable = false,
                initialCandidate = Sample(booleanArrayOf(), longArrayOf(1, 1, 1, 1))).use {
                assertIs<MinimizeResult.Optimal>(it.runSlice(ExecutionControl.Never, Long.MAX_VALUE, -1L) {})
            }

            assertEquals(36.0, result.objective)
            assertEquals(22.0, result.gapBound)
        }
    }
}
