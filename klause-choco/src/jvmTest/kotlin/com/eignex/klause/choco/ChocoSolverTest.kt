package com.eignex.klause.choco

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.IndomainMiddle
import com.eignex.klause.solver.backtrack.InputOrder
import com.eignex.klause.solver.backtrack.SearchTier
import com.eignex.klause.solver.backtrack.TierVarSelect
import com.eignex.klause.solver.backtrack.TieredVariableHeuristic
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
    fun `lcg fixed search with indomain_middle is sound (no false unsat)`() {
        // A permutation of [0..2] under AllDifferent is satisfiable. Mirroring an indomain_middle
        // fixed search onto the LCG engine with the classic makeIntEq decision operator produced a
        // false UNSAT here (rasros/choco-lcg-false-unsat); applyFixedSearch now branches such tiers
        // with makeIntSplit, so the reference stays sound.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        val tier = SearchTier(IntArray(0), intArrayOf(0, 1, 2), TierVarSelect.InputOrder, IndomainMiddle)
        // applyFixedSearch reads the tier's own value heuristic, not params.valueHeuristic.
        val fixed = BacktrackParams(variableHeuristic = TieredVariableHeuristic(listOf(tier), InputOrder))
        val r = ChocoSolver(p).solve(ChocoParams(lcg = true, fixedSearch = fixed))
        assertTrue(r is SolveResult.Sat, "expected Sat under LCG fixed indomain_middle, got $r")
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
}
