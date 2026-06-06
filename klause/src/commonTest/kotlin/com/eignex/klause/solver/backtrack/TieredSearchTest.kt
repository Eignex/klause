package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationSession
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
    fun `tier variables are picked before fallback variables`() {
        val session = PropagationSession(problemOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
        val tier = SearchTier(IntArray(0), intArrayOf(2), TierVarSelect.InputOrder, IndomainMin)
        val h = TieredVariableHeuristic(listOf(tier), InputOrder)
        assertEquals(VarRef.IntVar(2), h.pick(session, rng))
        session.pinInt(2, 0)
        // Tier exhausted: the fallback completes the rest in its own order.
        assertEquals(VarRef.IntVar(0), h.pick(session, rng))
    }

    @Test
    fun `earlier tiers win over later tiers`() {
        val session = PropagationSession(problemOf(IntDomain(0, 2), IntDomain(0, 2)))
        val first = SearchTier(IntArray(0), intArrayOf(1), TierVarSelect.InputOrder, IndomainMin)
        val second = SearchTier(IntArray(0), intArrayOf(0), TierVarSelect.InputOrder, IndomainMin)
        val h = TieredVariableHeuristic(listOf(first, second), InputOrder)
        assertEquals(VarRef.IntVar(1), h.pick(session, rng))
        session.pinInt(1, 0)
        assertEquals(VarRef.IntVar(0), h.pick(session, rng))
        session.pinInt(0, 0)
        assertNull(h.pick(session, rng))
    }

    @Test
    fun `smallest lower bound select picks the lowest domain minimum in the tier`() {
        val session = PropagationSession(problemOf(IntDomain(5, 9), IntDomain(3, 9), IntDomain(4, 9)))
        val tier = SearchTier(IntArray(0), intArrayOf(0, 1, 2), TierVarSelect.SmallestLowerBound, IndomainMin)
        val h = TieredVariableHeuristic(listOf(tier), InputOrder)
        assertEquals(VarRef.IntVar(1), h.pick(session, rng))
    }

    @Test
    fun `largest upper bound select picks the highest domain maximum in the tier`() {
        val session = PropagationSession(problemOf(IntDomain(0, 4), IntDomain(0, 7), IntDomain(0, 6)))
        val tier = SearchTier(IntArray(0), intArrayOf(0, 1, 2), TierVarSelect.LargestUpperBound, IndomainMin)
        val h = TieredVariableHeuristic(listOf(tier), InputOrder)
        assertEquals(VarRef.IntVar(1), h.pick(session, rng))
    }

    @Test
    fun `tiered values dispatch to the owning tier and fall back elsewhere`() {
        val session = PropagationSession(problemOf(IntDomain(0, 5), IntDomain(0, 5)))
        val tier = SearchTier(IntArray(0), intArrayOf(1), TierVarSelect.InputOrder, IndomainMax)
        val h = TieredValueHeuristic(listOf(tier), IndomainMin, numBoolVars = 0, numIntVars = 2)
        assertEquals(5, h.values(session, VarRef.IntVar(1), rng).first())
        assertEquals(0, h.values(session, VarRef.IntVar(0), rng).first())
    }
}
