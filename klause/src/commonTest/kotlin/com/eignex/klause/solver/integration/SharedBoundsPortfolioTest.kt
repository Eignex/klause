package com.eignex.klause.solver.integration

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.SequentialPortfolio
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Sharing the objective lower bound across backtrack arms must not change the proven optimum.
 * Each arm tightens its objective variable to the cross-arm maximum bound — a sound floor every feasible
 * solution meets — so a portfolio reaches the same answer it would with no bound sharing.
 */
class SharedBoundsPortfolioTest {

    @Test
    fun `lower-bound sharing preserves the optimum`() {
        // minimize 3x + 2y + z subject to x + y + z >= 4, all in [0..5]. Optimum = 8 (x=0,y=0... ) check:
        // minimize cost with x+y+z>=4; cheapest unit is z (coef 1), then y (2): z=4 → cost 4. Optimum = 4.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 4)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(3L, 2L, 1L))

        val scenario = PortfolioScenario(cores = 1, arms = 3, kind = Kind.COP, engine = EngineMix.BACKTRACK)
        val workers = PortfolioBuilder.build(problem.bake(), scenario, objective = obj)
        val result = SequentialPortfolio.exp3(workers).use { it.minimize() }
        assertIs<MinimizeResult.Optimal>(result)
        assertEquals(4.0, result.objectiveValue, "bound sharing must not change the optimum")
    }
}
