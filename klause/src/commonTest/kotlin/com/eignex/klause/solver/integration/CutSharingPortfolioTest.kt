package com.eignex.klause.solver.integration

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.SequentialPortfolio
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Wiring cut sharing into the portfolio (#809 phase 2) must not change the proven optimum: sharing only
 * adds globally-valid cuts, so a backtrack portfolio with [PortfolioScenario.shareCuts] on reaches the
 * same answer as with it off. Exercises PortfolioBuilder → BacktrackParams.cutExchange →
 * LpEngine.exchangeCuts end to end.
 */
class CutSharingPortfolioTest {

    @Test
    fun `cut sharing preserves the optimum`() {
        // minimize x + 2y subject to x + y >= 3, x,y in [0..5]. Optimum = 3 (x=3, y=0).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))

        fun optimum(shareCuts: Boolean): Double {
            val scenario = PortfolioScenario(
                cores = 1,
                arms = 3,
                kind = Kind.COP,
                engine = EngineMix.BACKTRACK,
                shareCuts = shareCuts,
            )
            val workers = PortfolioBuilder.build(problem.bake(), scenario, objective = obj)
            val result = SequentialPortfolio.exp3(workers).use { it.minimize() }
            return assertIs<MinimizeResult.Optimal>(result).objectiveValue
        }

        assertEquals(3.0, optimum(shareCuts = false))
        assertEquals(3.0, optimum(shareCuts = true), "sharing global cuts must not change the optimum")
    }

    @Test
    fun `cut sharing is on by default`() {
        val scenario = PortfolioScenario(cores = 1, arms = 3, kind = Kind.COP, engine = EngineMix.BACKTRACK)
        assertEquals(true, scenario.shareCuts, "global-cut sharing defaults on")
    }
}
