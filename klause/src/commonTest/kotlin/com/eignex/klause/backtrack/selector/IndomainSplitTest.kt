package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class IndomainSplitTest {

    private val rng = Random(1)

    @Test
    fun `indomain split midpoint does not overflow on a full long span`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(-1L, IndomainSplit.values(session, VarRef.IntVar(0), rng).first())
    }

    @Test
    fun `indomain split takes the floor midpoint on negative bounds`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(-9, -4)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(-7L, IndomainSplit.values(session, VarRef.IntVar(0), rng).first())
    }
}
