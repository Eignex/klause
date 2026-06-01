package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.bandit.univariate.UCB1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HeuristicPortfolioTest {

    private fun simpleAllDifferent(n: Int) = Problem(
        numBoolVars = 0,
        numIntVars = n,
        intDomains = Array(n) { IntDomain(0, n - 1) },
        factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = n)),
    )

    @Test
    fun `portfolio solves with default Bernoulli reward`() {
        val portfolio = HeuristicPortfolio.ucb1(
            listOf(
                HeuristicPortfolio.Arm("input+min", InputOrder, IndomainMin),
                HeuristicPortfolio.Arm("smallest+random", SmallestDomain, IndomainRandom),
            )
        )
        val r = BacktrackSolver(simpleAllDifferent(5)).solve(
            BacktrackParams(
                variableHeuristic = portfolio.variableHeuristic,
                valueHeuristic = portfolio.valueHeuristic,
                randomSeed = 0L,
            )
        )
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals((0..4).toSet(), sat.assignment.ints.toSet())
    }

    @Test
    fun `portfolio switches arms across restarts`() {
        val portfolio = HeuristicPortfolio.ucb1(
            listOf(
                HeuristicPortfolio.Arm("a", InputOrder, IndomainMin),
                HeuristicPortfolio.Arm("b", SmallestDomain, IndomainMax),
                HeuristicPortfolio.Arm("c", RandomVariable, IndomainRandom),
            )
        )
        val visited = HashSet<Int>()
        visited.add(portfolio.currentArmIndex)
        // Trigger several restarts manually via the var-heuristic onRestart hook.
        repeat(20) {
            portfolio.variableHeuristic.onRestart()
            portfolio.valueHeuristic.onRestart()
            visited.add(portfolio.currentArmIndex)
        }
        assertTrue(
            visited.size >= 2,
            "UCB1 should have explored at least 2 arms across 20 restarts; got ${visited.size}"
        )
    }

    @Test
    fun `portfolio rejects mismatched arm count and bandit size`() {
        val ex = runCatching {
            HeuristicPortfolio(
                arms = listOf(HeuristicPortfolio.Arm("a", InputOrder, IndomainMin)),
                bandit = MultiArmedBandit(nbrArms = 3, policy = UCB1()),
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IllegalArgumentException; got $ex")
    }

    @Test
    fun `portfolio with custom reward forwards run stats`() {
        var lastStats: HeuristicPortfolio.RunStats? = null
        val portfolio = HeuristicPortfolio(
            arms = listOf(
                HeuristicPortfolio.Arm("a", InputOrder, IndomainMin),
                HeuristicPortfolio.Arm("b", InputOrder, IndomainMax),
            ),
            bandit = MultiArmedBandit(nbrArms = 2, policy = UCB1()),
            rewardFn = { stats ->
                lastStats = stats
                if (stats.solutionsFound > 0) 1.0 else 0.0
            },
        )
        // Synthetic conflicts and a solution on the first run, then a restart.
        portfolio.variableHeuristic.onConflict(VarRef.IntVar(0))
        portfolio.variableHeuristic.onConflict(VarRef.IntVar(1))
        portfolio.variableHeuristic.onSolution(
            com.eignex.klause.solver.Sample(BooleanArray(0), intArrayOf(0))
        )
        portfolio.variableHeuristic.onRestart()
        assertEquals(2, lastStats!!.conflicts)
        assertEquals(1, lastStats!!.solutionsFound)
        // Stats reset on restart.
        portfolio.variableHeuristic.onRestart()
        assertEquals(0, lastStats!!.conflicts)
        assertEquals(0, lastStats!!.solutionsFound)
    }
}
