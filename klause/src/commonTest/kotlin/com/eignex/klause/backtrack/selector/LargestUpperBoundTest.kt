package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LargestUpperBoundTest {

    private val rng = Random(1)

    @Test
    fun `largest upper bound prefers the int with the highest maximum`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 7)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(VarRef.IntVar(1), LargestUpperBound.pick(session, rng))
    }
}
