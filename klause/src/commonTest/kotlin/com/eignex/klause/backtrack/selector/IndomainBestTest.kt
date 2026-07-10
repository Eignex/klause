package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class IndomainBestTest {

    @Test
    fun `IndomainBest descending for negative coefficient`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(-1L)) // maximise → try high first
        val session = PropagationSession(problem)
        val values = IndomainBest(obj).values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals(listOf(4L, 3L, 2L, 1L, 0L), values)
    }

    @Test
    fun `IndomainBest ascending for positive coefficient`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(2L))
        val session = PropagationSession(problem)
        val values = IndomainBest(obj).values(session, VarRef.IntVar(0), Random(0L)).toList()
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), values)
    }
}
