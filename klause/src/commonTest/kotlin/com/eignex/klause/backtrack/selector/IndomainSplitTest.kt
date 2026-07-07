package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class IndomainSplitTest {

    private val rng = Random(1)

    @Test
    fun `indomain split yields the interval midpoint first`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        val values = IndomainSplit.values(session, VarRef.IntVar(0), rng).toList()
        assertEquals(5L, values.first())
        // The trailing walk completes the domain without repeating the midpoint.
        assertEquals((0L..10L).toList().sorted(), values.sorted())
    }

    @Test
    fun `indomain split midpoint respects a shifted interval`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(10, 13)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(11L, IndomainSplit.values(session, VarRef.IntVar(0), rng).first())
    }
}
