package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Objective-bound propagation: for a single-variable objective (the shape every FlatZinc
 * `solve` goal reifies to), branch-and-bound pushes each incumbent's bound onto the objective
 * variable as a permanent unit at the root, so the constraint defining that variable propagates
 * the tightening backwards into the decision variables. The passive lower-bound predicate the
 * [LinearObjective] path uses only reads the objective variable's own domain — it cannot
 * tighten the inputs of a non-linear defining constraint. These tests guard that the mechanism
 * stays sound: the proven optimum must match brute force for both a linear-defined and a
 * product-defined (non-linear) objective variable.
 */
class ObjectiveBoundPropagationTest {

    @Test
    fun `bounds a product-defined objective variable to the proven optimum`() {
        // z = x * y, x + y <= 6, x,y in [0,5]. Maximise z. The objective is the single
        // variable z; only asserting z >= best+1 and propagating through Product prunes x,y.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 25)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.LE, bound = 6),
                Product(a = 0, b = 1, result = 2),
            ),
        )
        // maximise z → minimise -z.
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L, -1L))
        val params = BacktrackParams(randomSeed = 1L, maxDecisions = 200_000L)
        val terminal = BacktrackSolver(problem.bake()).minimize(obj, params)
        val optimal = assertIs<MinimizeResult.Optimal>(terminal)
        // Brute-force max of x*y under x+y<=6 is 3*3 = 9, so the minimised -z is -9.
        assertEquals(-9.0, optimal.objective, "must prove the product-defined optimum")
    }

    @Test
    fun `bounds a linear-defined objective variable to the proven optimum`() {
        // z = 2a + 3b, a in [0,4], b in [0,3]. Minimise z. The Linear factor defines z, the
        // objective points only at z; asserting z <= best-1 propagates back onto a,b.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 4), IntDomain(1, 3), IntDomain(0, 100)),
            factors = arrayOf<Factor>(
                Linear(
                    coeffs = intArrayOf(2, 3, -1),
                    vars = intArrayOf(0, 1, 2),
                    op = LinearOp.EQ,
                    bound = 0,
                ),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L))
        val params = BacktrackParams(randomSeed = 1L, maxDecisions = 200_000L)
        val terminal = BacktrackSolver(problem.bake()).minimize(obj, params)
        val optimal = assertIs<MinimizeResult.Optimal>(terminal)
        // min 2a+3b with a>=1,b>=1 is 2*1 + 3*1 = 5.
        assertEquals(5.0, optimal.objective, "must prove the linear-defined optimum")
    }
}
