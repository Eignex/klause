package com.eignex.klause.backtrack.selector

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaxSdSelectorTest {

    @Test
    fun `MaxSd drops infeasible probe values just like Impact`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(2, 2)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        val session = PropagationSession(problem)
        val values = MaxSd().values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertTrue(2 !in values, "MaxSd must drop infeasible value 2; got $values")
        assertEquals(setOf(0L, 1L, 3L), values.toSet())
    }

    @Test
    fun `MaxSd restores trail level after probing`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 5)),
        )
        val session = PropagationSession(problem)
        val levelBefore = session.decisionLevel
        MaxSd().values(session, VarRef.IntVar(0), Random(11L)).toList()
        assertEquals(levelBefore, session.decisionLevel)
    }
}
