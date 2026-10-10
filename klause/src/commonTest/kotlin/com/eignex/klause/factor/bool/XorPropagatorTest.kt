package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.propagate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class XorPropagatorTest {

    @Test
    fun `single unassigned var forced true to reach odd target`() {
        // v0=true, v1=true -> pinnedParity=0; target=1 -> v2 must be true
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        assertIs<PropagationResult.Implied>(session.pinBool(1, true))
        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `all vars assigned with wrong parity yields conflict`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }

}
