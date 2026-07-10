package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MaxRegretTest {

    @Test
    fun `MaxRegret picks variable with largest weighted span`() {
        // 3 int vars, all unpinned, domain widths and coefficients chosen so v1 wins.
        //   v0: dom [0..4] (width 4), coeff 1 → regret 4
        //   v1: dom [0..3] (width 3), coeff 5 → regret 15
        //   v2: dom [0..9] (width 9), coeff 0 → regret 0
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 3), IntDomain(0, 9)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 5L, 0L))
        val session = PropagationSession(problem)
        val picked = MaxRegret(obj).pick(session, Random(0L))
        assertEquals(VarRef.IntVar(1), picked)
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
