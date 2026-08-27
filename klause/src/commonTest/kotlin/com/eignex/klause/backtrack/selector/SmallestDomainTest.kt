package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
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

    @Test
    fun `first-fail still orders a column whose bounds span more than a Long can count`() {
        // The full-width column holds the most values of any domain; a count that wrapped would
        // make it look like the smallest and invert the first-fail order.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE), IntDomain(0, 9)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)

        assertEquals(VarRef.IntVar(1), SmallestDomain.pick(session, Random(1)))
        assertEquals(VarRef.IntVar(0), LargestDomain.pick(session, Random(1)))
    }
}
