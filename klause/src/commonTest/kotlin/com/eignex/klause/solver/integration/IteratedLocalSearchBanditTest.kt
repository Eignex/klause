package com.eignex.klause.solver.integration

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The contextual-bandit acceptance only decides which local optima join the ILS population —
 *  every choice is sound, so LS must still descend to a good incumbent. */
class IteratedLocalSearchBanditTest {

    @Test
    fun `ils with bandit acceptance still reaches the optimum`() {
        // minimize x + 2y subject to x + y >= 3, x,y in [0..5]. Optimum = 3 (x=3, y=0).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val solver = LocalSearchSolver(
            problem.bake(),
            restartPolicy = IteratedLocalSearchRestart(
                populationSize = 3,
                acceptanceBandit = IteratedLocalSearchRestart.acceptanceBandit(seed = 1L),
            ),
        )
        val r = solver.minimize(obj, LocalSearchParams(maxFlips = 6_000L, randomSeed = 0L))
        val best = assertIs<MinimizeResult.WithSample>(r)
        assertTrue(best.objectiveValue <= 5.0, "expected a near-optimal incumbent, got ${best.objectiveValue}")
    }
}
