package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MaxRegretTest {

    @Test
    fun `MaxRegret regret saturates instead of wrapping on a full long span`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1_000_000L, 2L))
        val session = PropagationSession(problem)
        assertEquals(VarRef.IntVar(1), MaxRegret(obj).pick(session, Random(0L)))
    }

    @Test
    fun `MaxRegret falls through to base when all regrets are zero`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            factors = emptyArray(),
        )
        // Zero coefficients → all regrets 0; base = InputOrder returns v0.
        val obj = LinearObjective(intCoefficients = longArrayOf(0L, 0L))
        val session = PropagationSession(problem)
        val picked = MaxRegret(obj, base = InputOrder).pick(session, Random(0L))
        assertEquals(VarRef.IntVar(0), picked)
    }
}
