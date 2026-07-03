package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SequentialPortfolioTest {

    private fun exactlyOneOver(n: Int): Problem = Problem(
        numBoolVars = n,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Cardinality.exactlyOne(IntArray(n) { Lit.make(it, true) }),
        ),
    )

    private fun btArms(problem: Problem, n: Int, objective: LinearObjective? = null): List<PortfolioWorker> =
        List(n) { i ->
            PortfolioWorker.of(
                "bt#$i",
                BacktrackSolver(problem).session(),
                BacktrackParams(randomSeed = i.toLong()),
                objective = objective,
                withBound = { p, supplier -> p.copy(objectiveBoundSupplier = supplier) },
            )
        }

    @Test
    fun `sequential solve on a satisfiable problem returns sat`() {
        val r = SequentialPortfolio.exp3(btArms(exactlyOneOver(4), 3)).use { it.solve() }
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.bools.count { it }, "exactly-one violated")
    }

    @Test
    fun `sequential solve on an unsat problem returns unsat`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        assertIs<SolveResult.Unsat>(SequentialPortfolio.exp3(btArms(problem, 2)).use { it.solve() })
    }

    @Test
    fun `sequential minimize exhausts a small problem and proves the optimum`() {
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
        val r = SequentialPortfolio.exp3(btArms(problem, 3, obj)).use { it.minimize() }
        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(r).objectiveValue)
    }

    @Test
    fun `aggressive re-seeding does not disrupt proving the optimum`() {
        // The re-seed guard (#3): even with reseedStaleThreshold = 1 (drop a resumable arm's handle the
        // first non-improving segment), a fast optimality proof must still come back as Optimal at the
        // true value — the terminal-verdict and incumbent-exists guards keep re-seed from corrupting it.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val r = SequentialPortfolio.exp3(btArms(problem, 3, obj), reseedStaleThreshold = 1).use { it.minimize() }
        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(r).objectiveValue)
    }

    @Test
    fun `ucb1 policy factory also proves the optimum`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val r = SequentialPortfolio.ucb1(btArms(problem, 3, obj)).use { it.minimize() }
        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(r).objectiveValue)
    }
}
