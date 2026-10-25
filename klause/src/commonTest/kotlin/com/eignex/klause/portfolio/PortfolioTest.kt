package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.bandit.univariate.UCB1
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PortfolioTest {
    @Test
    fun `probe weights should redistribute the fixed initial pool budget`() {
        val eligible = listOf(0, 1, 2)
        val uniform = eligible.map { initialProbeFraction(it, eligible, intArrayOf()) }

        val weighted = eligible.map { initialProbeFraction(it, eligible, intArrayOf(3, 1, 1)) }

        assertEquals(1.0, uniform.sum(), 1e-12)
        assertEquals(1.0, weighted.sum(), 1e-12)
        assertTrue(weighted[0] > uniform[0])
        assertTrue(weighted.drop(1).all { it < uniform[0] })
    }

    @Test
    fun `sequential solve should return sat on a satisfiable problem`() {
        val r = Portfolio.thompson(btArms(exactlyOneOver(4), 3)).use { it.solve() }
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.bools.count { it })
    }

    @Test
    fun `a satisfaction arm should resume one handle across its segments`() {
        val solver = CountingResumableSolver(slicesToVerdict = 4)
        val worker = PortfolioWorker.of("bt", 0, solver.session(), BacktrackParams())

        val r = Portfolio.thompson(listOf(worker)).use { it.solve() }

        assertIs<SolveResult.Sat>(r)
        assertEquals(1, solver.opened.size)
        assertEquals(4, solver.opened.single().slices)
    }

    @Test
    fun `a resumed local search arm should respect session pins`() {
        val session = LocalSearchSolver(Problem(1, 0, emptyArray(), emptyArray()).bake()).session()
        session.push(Assumptions(bools = mapOf(0 to true)))
        val worker = PortfolioWorker.of(
            "ls",
            0,
            session,
            LocalSearchParams(initialAssignment = Sample(booleanArrayOf(false), longArrayOf()), randomSeed = 3L),
        )

        val result = Portfolio.thompson(listOf(worker)).use { it.solve() }

        assertTrue(assertIs<SolveResult.Sat>(result).assignment.bools[0])
    }

    @Test
    fun `replicas of one arm should be reported under numbered labels`() {
        val workers = List(3) { index ->
            PortfolioWorker.of("bt", index, CountingResumableSolver(slicesToVerdict = 20).session(), BacktrackParams())
        }

        val r = Portfolio.thompson(workers).use { it.solve() }

        assertEquals(listOf("bt", "bt#2", "bt#3"), r.stats.portfolio.arms.map { it.label })
    }

    @Test
    fun `sequential solve should return unsat on an unsat problem`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        assertIs<SolveResult.Unsat>(Portfolio.thompson(btArms(problem, 2)).use { it.solve() })
    }

    @Test
    fun `sequential minimize should exhaust a small problem and prove the optimum`() {
        // Optimum 3 at x=3, y=0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val r = Portfolio.thompson(btArms(problem, 3, obj)).use { it.minimize() }
        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(r).objectiveValue)
    }

    @Test
    fun `any kumulant policy should also prove the optimum`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val arms = btArms(problem, 3, obj)
        val r = Portfolio(arms, MultiArmedBandit(arms.size, UCB1(), Random(0))).use { it.minimize() }
        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(r).objectiveValue)
    }
}
