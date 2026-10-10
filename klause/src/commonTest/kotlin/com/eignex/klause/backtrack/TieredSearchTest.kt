package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TieredSearchTest {

    private fun problemOf(vararg domains: IntDomain): Problem = Problem(
        numBoolVars = 0,
        numIntVars = domains.size,
        intDomains = arrayOf(*domains),
        factors = arrayOf<Factor>(),
    )

    private val rng = Random(1)

    @Test
    fun `earlier tiers win over later tiers`() {
        val session = PropagationSession(problemOf(IntDomain(0, 2), IntDomain(0, 2)))
        val first = SearchTier(IntArray(0), intArrayOf(1), TierVarSelect.InputOrder, IndomainMin)
        val second = SearchTier(IntArray(0), intArrayOf(0), TierVarSelect.InputOrder, IndomainMin)
        val h = TieredVariableSelector(listOf(first, second), InputOrder)
        assertEquals(VarRef.IntVar(1), h.pick(session, rng))
        session.pinInt(1, 0)
        assertEquals(VarRef.IntVar(0), h.pick(session, rng))
        session.pinInt(0, 0)
        assertNull(h.pick(session, rng))
    }

    @Test
    fun `tiered values dispatch to the owning tier and fall back elsewhere`() {
        val session = PropagationSession(problemOf(IntDomain(0, 5), IntDomain(0, 5)))
        val tier = SearchTier(IntArray(0), intArrayOf(1), TierVarSelect.InputOrder, IndomainMax)
        val h = TieredValueSelector(listOf(tier), IndomainMin, numBoolVars = 0, numIntVars = 2)
        assertEquals(5, h.values(session, VarRef.IntVar(1), rng).first())
        assertEquals(0, h.values(session, VarRef.IntVar(0), rng).first())
    }
}
