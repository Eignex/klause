package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChbTest {

    private val rng = Random(1)

    @Test
    fun `chb branches first on the variable most recently in a conflict`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = arrayOf<IntDomain>(),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        val chb = Chb()
        // No conflicts yet: all scores are 0, so the tie breaks to the lowest var id.
        assertEquals(VarRef.Bool(0), chb.pick(session, rng))
        // A conflict implicating var 2 lifts its Q above every untouched variable.
        chb.onConflict(VarRef.Bool(2), PropagationResult.Unsat(conflictBools = intArrayOf(2)))
        assertEquals(VarRef.Bool(2), chb.pick(session, rng))
    }

    @Test
    fun `chb solves a satisfiable clause problem with a valid witness`() {
        // (x0 ∨ x1) ∧ (¬x0 ∨ x2) ∧ (¬x1 ∨ ¬x2): satisfiable.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = arrayOf<IntDomain>(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
            ),
        )
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 1L, variableSelector = Chb())),
        )
        val b = sat.assignment.bools
        assertTrue(b[0] || b[1])
        assertTrue(!b[0] || b[2])
        assertTrue(!b[1] || !b[2])
    }

    @Test
    fun `chb reports unsat on a contradiction`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = arrayOf<IntDomain>(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        assertIs<SolveResult.Unsat>(
            BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 1L, variableSelector = Chb())),
        )
    }
}
