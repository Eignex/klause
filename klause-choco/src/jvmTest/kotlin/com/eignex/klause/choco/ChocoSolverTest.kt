package com.eignex.klause.choco

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChocoSolverTest {

    @Test
    fun `solves a satisfiable clause problem`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val r = ChocoSolver(p).solve(ChocoParams())
        assertTrue(r is SolveResult.Sat)
        assertTrue(r.assignment.bools[0] || r.assignment.bools[1])
    }

    @Test
    fun `proves unsat`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        assertTrue(ChocoSolver(p).solve(ChocoParams()) is SolveResult.Unsat)
    }

    @Test
    fun `enumerates a permutation domain`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        val models = ChocoSolver(p).enumerate(ChocoParams()).toList()
        assertEquals(6, models.size) // 3! permutations
    }

    @Test
    fun `minimizes a linear objective`() {
        // minimize x with x in [2,9]; optimum is 2.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 9)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))
        val r = ChocoSolver(p).minimize(obj, ChocoParams())
        assertTrue(r is MinimizeResult.Optimal, "expected Optimal, got $r")
        assertEquals(2.0, r.objective)
    }
}

class ChocoParallelPortfolioTest {

    @Test
    fun `parallel solve finds a model and parallel unsat is proven`() {
        val sat = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val r = ChocoSolver(sat).solve(ChocoParams(workers = 3))
        assertTrue(r is SolveResult.Sat)
        assertTrue(r.assignment.bools[0] || r.assignment.bools[1])

        val unsat = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        assertTrue(ChocoSolver(unsat).solve(ChocoParams(workers = 3)) is SolveResult.Unsat)
    }

    @Test
    fun `parallel minimize proves the optimum`() {
        // minimize x + 2y subject to x + y >= 3 over [0..5]^2; optimum 3 at (3, 0).
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val r = ChocoSolver(p).minimize(obj, ChocoParams(workers = 3))
        val opt = r as MinimizeResult.Optimal
        assertEquals(3.0, opt.objective)
    }
}
