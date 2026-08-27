package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.backtrack.selector.boundsMidpoint
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.search.SearchDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchIntNodeTest {

    private fun problemOf(vararg domains: IntDomain): Problem = Problem(
        numBoolVars = 0,
        numIntVars = domains.size,
        intDomains = arrayOf(*domains),
        factors = arrayOf<Factor>(),
    )

    @Test
    fun `a non-enumerable domain decision splits at the midpoint rather than the boundary`() {
        val wideHi = 3_000_000_000L // span > 2^31, so the domain is non-enumerable
        val session = PropagationSession(problemOf(IntDomain(0, wideHi)))
        assertNull(session.intDomain(0).spanOrNull())
        // The bounds midpoint of the pre-decision domain (the returned decision narrows it when applied).
        val mid = boundsMidpoint(session.intDomain(0))
        // `indomain_min` offers `min` as the preferred value; on a wide domain the node must still bisect,
        // else it peels one value per level (O(span) branch depth) instead of O(log span).
        val out = splitIntAlternatives(session, VarRef.IntVar(0), preferred = 0L).first()
        assertEquals(SearchDecision.IntAtMost(0, mid), out, "wide-domain split point must be the bounds midpoint")
        assertTrue(mid in 1L until wideHi, "the midpoint is strictly interior, so both children are non-empty")
    }

    @Test
    fun `an enumerable domain still splits at the value heuristic's preferred value`() {
        val session = PropagationSession(problemOf(IntDomain(0, 10)))
        assertNotNull(session.intDomain(0).spanOrNull())
        val out = splitIntAlternatives(session, VarRef.IntVar(0), preferred = 0L).first()
        assertEquals(SearchDecision.IntAtMost(0, 0L), out, "enumerable-domain behavior is unchanged")
    }
}
