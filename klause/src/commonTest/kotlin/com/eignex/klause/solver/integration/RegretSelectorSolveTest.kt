package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainBest
import com.eignex.klause.backtrack.selector.MaxRegret
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegretSelectorSolveTest {

    @Test
    fun `MaxRegret + IndomainBest solve a minimisation cleanly`() {
        // minimize x + 2y subject to x + y >= 3, x ∈ [0..5], y ∈ [0..5].
        // Optimal: x = 3, y = 0, obj = 3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.GE,
                    bound = 3,
                ),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val r = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(
                variableSelector = MaxRegret(obj),
                valueSelector = IndomainBest(obj),
                randomSeed = 0L,
            ),
        )
        val opt = assertIs<MinimizeResult.Optimal>(r)
        assertEquals(3.0, opt.objectiveValue)
        assertEquals(3L, opt.sample.ints[0])
        assertEquals(0L, opt.sample.ints[1])
    }
}
