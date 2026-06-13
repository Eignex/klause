package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.SelectorPortfolio
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.bandit.univariate.UCB1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectorPortfolioTest {

    private fun simpleAllDifferent(n: Int) = Problem(
        numBoolVars = 0,
        numIntVars = n,
        intDomains = Array(n) { IntDomain(0, n - 1) },
        factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = n)),
    )

    @Test
    fun `portfolio solves with default Bernoulli reward`() {
        val portfolio = SelectorPortfolio.ucb1(
            listOf(
                SelectorPortfolio.Arm("input+min", InputOrder, IndomainMin),
                SelectorPortfolio.Arm("smallest+random", SmallestDomain, IndomainRandom),
            ),
        )
        val r = BacktrackSolver(simpleAllDifferent(5)).solve(
            BacktrackParams(
                variableSelector = portfolio.variableSelector,
                valueSelector = portfolio.valueSelector,
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals((0..4).toSet(), sat.assignment.ints.toSet())
    }

    @Test
    fun `portfolio switches arms across restarts`() {
        val portfolio = SelectorPortfolio.ucb1(
            listOf(
                SelectorPortfolio.Arm("a", InputOrder, IndomainMin),
                SelectorPortfolio.Arm("b", SmallestDomain, IndomainMax),
                SelectorPortfolio.Arm("c", RandomVariable, IndomainRandom),
            ),
        )
        val visited = HashSet<Int>()
        visited.add(portfolio.currentArmIndex)
        // Trigger several restarts manually via the var-selector onRestart hook.
        repeat(20) {
            portfolio.variableSelector.onRestart()
            portfolio.valueSelector.onRestart()
            visited.add(portfolio.currentArmIndex)
        }
        assertTrue(
            visited.size >= 2,
            "UCB1 should have explored at least 2 arms across 20 restarts; got ${visited.size}",
        )
    }

    @Test
    fun `portfolio rejects mismatched arm count and bandit size`() {
        val ex = runCatching {
            SelectorPortfolio(
                arms = listOf(SelectorPortfolio.Arm("a", InputOrder, IndomainMin)),
                bandit = MultiArmedBandit(nbrArms = 3, policy = UCB1()),
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IllegalArgumentException; got $ex")
    }

    @Test
    fun `portfolio with custom reward forwards run stats`() {
        var lastStats: SelectorPortfolio.RunStats? = null
        val portfolio = SelectorPortfolio(
            arms = listOf(
                SelectorPortfolio.Arm("a", InputOrder, IndomainMin),
                SelectorPortfolio.Arm("b", InputOrder, IndomainMax),
            ),
            bandit = MultiArmedBandit(nbrArms = 2, policy = UCB1()),
            rewardFn = { stats ->
                lastStats = stats
                if (stats.solutionsFound > 0) 1.0 else 0.0
            },
        )
        // Synthetic conflicts and a solution on the first run, then a restart.
        portfolio.variableSelector.onConflict(VarRef.IntVar(0))
        portfolio.variableSelector.onConflict(VarRef.IntVar(1))
        portfolio.variableSelector.onSolution(
            Sample(BooleanArray(0), intArrayOf(0)),
        )
        portfolio.variableSelector.onRestart()
        val firstStats = requireNotNull(lastStats)
        assertEquals(2, firstStats.conflicts)
        assertEquals(1, firstStats.solutionsFound)
        // Stats reset on restart.
        portfolio.variableSelector.onRestart()
        val resetStats = requireNotNull(lastStats)
        assertEquals(0, resetStats.conflicts)
        assertEquals(0, resetStats.solutionsFound)
    }
}
