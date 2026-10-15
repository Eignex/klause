package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SmallestLowerBoundTest {

    private val rng = Random(1)

    @Test
    fun `smallest lower bound should prefer the int with the lowest minimum`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(2, 9), IntDomain(-3, 9)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(VarRef.IntVar(1), SmallestLowerBound.pick(session, rng))
    }
}
