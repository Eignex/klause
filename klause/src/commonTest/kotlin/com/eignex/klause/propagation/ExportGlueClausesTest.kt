package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `skipPermanent` gate on [PropagationSession.exportGlueClauses] (#644 soundness): permanent clauses
 * are the search-conditioned assertions (the incumbent objective bound, blocking nogoods), valid only
 * under the current search's incumbent/assumptions; a caller learning under pins (LNS repair) must not
 * share them. Ordinary 1UIP learned clauses (globally-valid resolvents) are still exported.
 */
class ExportGlueClausesTest {

    @Test
    fun `skipPermanent excludes permanent clauses but keeps ordinary learned clauses`() {
        val session = PropagationSession(Problem(3, 0, emptyArray(), emptyList()))
        val ordinary = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val permanent = Clause(intArrayOf(Lit.make(2, true)))
        session.addLearnedClause(ordinary, lbd = 2, permanent = false)
        session.addLearnedClause(permanent, lbd = 1, permanent = true)

        val all = session.exportGlueClauses(maxLbd = 4, maxLen = 8, skipPermanent = false)
        val gated = session.exportGlueClauses(maxLbd = 4, maxLen = 8, skipPermanent = true)
        assertEquals(2, all.size, "both exported by default")
        assertEquals(1, gated.size, "the permanent search-conditioned clause is withheld")
    }
}
