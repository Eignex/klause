package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class IndomainRandomTest {

    @Test
    fun `random head on a non-enumerable domain is drawn from the full bounds`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        // Positional sampling could only ever reach [MIN, MIN + 2^31); over a handful of draws a
        // bounds-uniform head lands outside that window with overwhelming probability.
        val rng = Random(7)
        val heads = List(8) { IndomainRandom.values(session, VarRef.IntVar(0), rng).first() }
        assertTrue(heads.any { it > Long.MIN_VALUE + Int.MAX_VALUE.toLong() })
    }
}
