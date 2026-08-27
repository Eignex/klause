package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class DomainMaxRegretTest {

    private val rng = Random(1)

    @Test
    fun `max regret prefers the int with the largest gap between its two smallest values`() {
        // var 0: {0,1,2,3} regret 1; var 1: {0,2,3} (1 excluded) regret 2 — var 1 wins.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3).excludeValue(1)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(VarRef.IntVar(1), DomainMaxRegret.pick(session, rng))
    }
}
