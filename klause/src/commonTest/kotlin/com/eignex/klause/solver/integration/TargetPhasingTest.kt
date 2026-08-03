package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Target phasing and rephasing (#204) are heuristics layered on top of phase saving — they
 * reorder which polarity a fresh decision tries first but must never change the *set* of
 * feasible assignments nor the satisfiability verdict. These tests pin that invariant down.
 */
class TargetPhasingTest {

    private fun clauseProblem(): Problem = Problem(
        numBoolVars = 4,
        numIntVars = 0,
        intDomains = arrayOf<IntDomain>(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
        ),
    )

    @Test
    fun `target phasing finds a valid witness on a satisfiable problem`() {
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(clauseProblem().bake()).solve(
                BacktrackParams(randomSeed = 7L, targetPhasing = true, rephaseInterval = 2L),
            ),
        )
        val b = sat.assignment.bools
        assertTrue(b[0] || b[1])
        assertTrue(!b[1] || b[2])
        assertTrue(!b[2] || b[3])
        assertTrue(!b[0] || !b[3])
    }

    @Test
    fun `target phasing reports unsat on a contradiction`() {
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
            BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 1L, targetPhasing = true)),
        )
    }

    @Test
    fun `target phasing enumerates exactly the same models as plain phase saving`() {
        fun enumerate(params: BacktrackParams): Set<List<Boolean>> = BacktrackSolver(
            clauseProblem().bake(),
        ).enumerate(params)
            .map { it.bools.toList() }
            .toSet()

        val plain = enumerate(BacktrackParams(randomSeed = 3L, phaseSaving = true))
        // A tiny rephase interval forces the polarity source to rotate through every mode
        // during the enumeration, so all rephase branches are exercised.
        val targeted = enumerate(BacktrackParams(randomSeed = 3L, targetPhasing = true, rephaseInterval = 1L))
        assertTrue(plain.isNotEmpty())
        assertEquals(plain, targeted, "rephasing must not change the feasible set")
    }
}
