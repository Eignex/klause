package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

class ClauseDbTest {

    // Implication chain a -> b -> c, so a learned (!a | !b | c) vivifies to (!a | c): probing a=true
    // propagates b and c, falsifying !b (dropped) and satisfying c (prefix proven implied).
    private fun chainProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
        ),
    )

    private fun vivifiedLiterals(nativeSat: Boolean): Set<Int> {
        val baked = chainProblem().bake()
        val session = PropagationSession(baked, nativeSat = nativeSat)
        assertEquals(nativeSat, session.usesNativeSat)
        session.addLearnedClause(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            lbd = 3,
        )
        vivify(session, BacktrackParams(vivification = true, vivifyBatch = 8), 0)
        assertEquals(1, session.learnedClauseCount)
        return session.learnedClauseLiterals(0).toSet()
    }

    @Test
    fun `vivify should strengthen a learned clause on the general lane`() {
        assertEquals(setOf(Lit.make(0, false), Lit.make(2, true)), vivifiedLiterals(nativeSat = false))
    }

    @Test
    fun `vivify should strengthen a learned clause in the native-SAT arena store`() {
        assertEquals(setOf(Lit.make(0, false), Lit.make(2, true)), vivifiedLiterals(nativeSat = true))
    }

    @Test
    fun `a vivified clause should inherit its parent's LBD`() {
        val baked = chainProblem().bake()
        val session = PropagationSession(baked)
        session.addLearnedClause(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            lbd = 1,
        )
        vivify(session, BacktrackParams(vivification = true, vivifyBatch = 8), 0)
        assertEquals(2, session.learnedClauseLiterals(0).size)
        assertEquals(1, session.learnedClauseLbd(0), "the subclause keeps the parent's glue standing")
    }
}
