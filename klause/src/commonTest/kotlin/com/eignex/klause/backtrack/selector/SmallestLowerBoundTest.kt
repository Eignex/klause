package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SmallestLowerBoundTest {

    private val rng = Random(1)

    @Test
    fun `smallest lower bound prefers the int with the lowest minimum`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(2, 9), IntDomain(-3, 9)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        // Free bool counts as minimum 0; int 1's minimum of -3 undercuts it.
        assertEquals(VarRef.IntVar(1), SmallestLowerBound.pick(session, rng))
    }

    @Test
    fun `smallest lower bound counts free bools as zero`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(2, 9)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(VarRef.Bool(0), SmallestLowerBound.pick(session, rng))
    }
}
