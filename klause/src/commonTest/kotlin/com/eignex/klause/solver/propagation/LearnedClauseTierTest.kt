package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Three-tier learned-clause DB metadata plumbing (#201): per-clause tier and reuse flags must
 * default correctly, be mutable, respond to [PropagationState.noteLearnedUse], and survive the
 * compaction performed by [PropagationState.forgetLearnedClauses] in lockstep with the clauses.
 */
class LearnedClauseTierTest {

    // One static factor so learned-clause factor ids start above 0 — lets the tests probe the
    // "fid below numFactors is not a learned clause" guard in noteLearnedUse.
    private fun emptyState(numBool: Int): PropagationState {
        val p = Problem(
            numBoolVars = numBool,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        return PropagationState(p, Assumptions.None)
    }

    @Test
    fun `learned clauses start unclassified and unused`() {
        val s = emptyState(3)
        val fid = s.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))), lbd = 4)
        val idx = fid - s.problem.numFactors
        assertEquals(TIER_UNSET, s.learnedClauseTier(idx))
        assertFalse(s.learnedClauseUsedSinceReduction(idx))
    }

    @Test
    fun `noteLearnedUse marks the clause used and clear resets it`() {
        val s = emptyState(3)
        val fid = s.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))), lbd = 4)
        val idx = fid - s.problem.numFactors
        s.noteLearnedUse(fid)
        assertTrue(s.learnedClauseUsedSinceReduction(idx))
        s.clearLearnedClauseUsed(idx)
        assertFalse(s.learnedClauseUsedSinceReduction(idx))
        // A factor id that isn't a learned clause is ignored.
        s.noteLearnedUse(0)
        assertFalse(s.learnedClauseUsedSinceReduction(idx))
    }

    @Test
    fun `forget compacts tier and used flags in lockstep with surviving clauses`() {
        val s = emptyState(4)
        // Three learned clauses; tag each with a distinct tier and reuse flag.
        val f0 = s.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))), lbd = 2)
        val f1 = s.addLearnedClause(Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))), lbd = 5)
        val f2 = s.addLearnedClause(Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))), lbd = 9)
        val base = s.problem.numFactors
        s.setLearnedClauseTier(f0 - base, TIER_CORE)
        s.setLearnedClauseTier(f1 - base, TIER_MID)
        s.setLearnedClauseTier(f2 - base, TIER_LOCAL)
        s.noteLearnedUse(f2) // mark the third clause used

        // Drop the middle clause (index 1); keep indices 0 and 2.
        s.forgetLearnedClauses { idx, _ -> idx != 1 }

        assertEquals(2, s.learnedClauses.size)
        // Old index 0 stays at 0 (core); old index 2 slides to 1 (local, still used).
        assertEquals(TIER_CORE, s.learnedClauseTier(0))
        assertFalse(s.learnedClauseUsedSinceReduction(0))
        assertEquals(TIER_LOCAL, s.learnedClauseTier(1))
        assertTrue(s.learnedClauseUsedSinceReduction(1), "the surviving used clause must keep its reuse flag")
    }
}
