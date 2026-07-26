package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class SmallestDomainTest {

    @Test
    fun `smallest domain discriminates between wide domains past int saturation`() {
        // Both domains saturate Int-typed size at Int.MAX_VALUE; the Long count still orders them.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 6_000_000_000L), IntDomain(0, 5_000_000_000L)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        assertEquals(VarRef.IntVar(1), SmallestDomain.pick(session, Random(1)))
        assertEquals(VarRef.IntVar(0), LargestDomain.pick(session, Random(1)))
    }
}
