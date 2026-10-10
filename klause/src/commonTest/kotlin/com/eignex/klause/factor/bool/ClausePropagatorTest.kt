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

class ClausePropagatorTest {

    @Test
    fun `two of three false forces last to true`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, false))
        assertIs<PropagationResult.Implied>(session.pinBool(1, false))
        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `all literals false yields conflict`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            ),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }

}
