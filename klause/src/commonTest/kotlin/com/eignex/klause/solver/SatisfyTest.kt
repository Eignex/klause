package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SatisfyTest {

    @Test
    fun `satisfy returns Sat when assumptions are consistent`() {
        // Two free bools, no constraints. Pin b0=true; expect Sat with b0=true.
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val r = BacktrackSolver(problem).satisfyUnderAssumptions(
            Assumptions(bools = mapOf(0 to true)),
            BacktrackParams(),
        )
        val sat = assertIs<SatisfyResult.Sat>(r)
        assertEquals(true, sat.sample.bools[0])
    }

    @Test
    fun `satisfy projects seed conflict to assumption subset`() {
        // Unit clause forces b0=true. Pinning b0=false should fail at seed time, and the
        // returned core should mention exactly the pin that conflicts (b0), not unrelated
        // pins like b2.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, positive = true)))),
        )
        val r = BacktrackSolver(problem).satisfyUnderAssumptions(
            Assumptions(bools = mapOf(0 to false, 2 to true)),
            BacktrackParams(),
        )
        val unsat = assertIs<SatisfyResult.UnsatUnderAssumptions>(r)
        val core = unsat.core
        assertTrue(0 in core.boolKeys.toList(), "core should mention b0")
        assertEquals(false, core.boolValueOrNull(0))
        // b2 is an irrelevant assumption — not part of the conflict.
        assertTrue(2 !in core.boolKeys.toList(), "core should not mention irrelevant b2")
    }

    @Test
    fun `satisfy returns GloballyUnsat when no assumptions and bake fails`() {
        // Two unit clauses forcing both polarities of b0 — unsat with empty assumptions.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, positive = true))),
                Clause(intArrayOf(Lit.make(0, positive = false))),
            ),
        )
        val r = BacktrackSolver(problem).satisfyUnderAssumptions(
            Assumptions.None,
            BacktrackParams(),
        )
        assertIs<SatisfyResult.GloballyUnsat>(r)
    }

    @Test
    fun `minimizeCore strips irrelevant pins`() {
        // Hard: ¬b0 ∨ ¬b1. Assume b0=true, b1=true, b2=true (b2 irrelevant).
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))),
        )
        val r = BacktrackSolver(problem).satisfyUnderAssumptions(
            Assumptions(bools = mapOf(0 to true, 1 to true, 2 to true)),
            BacktrackParams(),
            minimizeCore = true,
        )
        val unsat = assertIs<SatisfyResult.UnsatUnderAssumptions>(r)
        // Core must contain the load-bearing b0, b1 and exclude irrelevant b2.
        assertEquals(true, unsat.core.boolValueOrNull(0))
        assertEquals(true, unsat.core.boolValueOrNull(1))
        assertTrue(2 !in unsat.core.boolKeys.toList())
    }

    @Test
    fun `engine populates Unsat assumptionCore from 1UIP decision levels`() {
        // Hard: ¬b0 ∨ ¬b1. Assume b0=true, b1=true, b2=true (b2 irrelevant). The seed
        // phase catches this so the conflictLevels include b0 and b1 but not b2 —
        // SolveResult.Unsat.assumptionCore should expose the same subset without any
        // deletion-MUS work (minimizeCore = false).
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false)))),
        )
        val r = BacktrackSolver(problem).solve(
            com.eignex.klause.solver.backtrack.BacktrackParams(
                assumptions = Assumptions(bools = mapOf(0 to true, 1 to true, 2 to true)),
            ),
        )
        val unsat = assertIs<SolveResult.Unsat>(r)
        val ac = unsat.assumptionCore
        assertTrue(ac != null && !ac.isEmpty, "engine should populate assumptionCore")
        assertEquals(true, ac.boolValueOrNull(0))
        assertEquals(true, ac.boolValueOrNull(1))
        assertTrue(2 !in ac.boolKeys.toList())
    }

    @Test
    fun `minimizeCore on satisfiable input still returns Sat`() {
        val problem = Problem(numBoolVars = 2, numIntVars = 0, intDomains = emptyArray(), factors = emptyArray())
        val r = BacktrackSolver(problem).satisfyUnderAssumptions(
            Assumptions(bools = mapOf(0 to true)),
            BacktrackParams(),
            minimizeCore = true,
        )
        assertIs<SatisfyResult.Sat>(r)
    }

    @Test
    fun `satisfy returns UnsatUnderAssumptions for pairwise-conflicting bool pins`() {
        // x XOR y forces them to differ. Pinning both true must fail.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, positive = false), Lit.make(1, positive = false))),
                Clause(intArrayOf(Lit.make(0, positive = true), Lit.make(1, positive = true))),
            ),
        )
        val r = BacktrackSolver(problem).satisfyUnderAssumptions(
            Assumptions(bools = mapOf(0 to true, 1 to true)),
            BacktrackParams(),
        )
        val unsat = assertIs<SatisfyResult.UnsatUnderAssumptions>(r)
        // Both pins must be in the core — neither alone is unsat.
        assertEquals(true, unsat.core.boolValueOrNull(0))
        assertEquals(true, unsat.core.boolValueOrNull(1))
    }
}
