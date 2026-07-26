package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class IndomainMiddleTest {

    private val rng = Random(1)

    @Test
    fun `indomain middle mean of bounds does not overflow on a full long span`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)), arrayOf<Factor>())
        val session = PropagationSession(problem)
        assertEquals(-1L, IndomainMiddle.values(session, VarRef.IntVar(0), rng).first())
    }

    @Test
    fun `centered walk terminates near the long range ends`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 2)),
            arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)
        val values = IndomainMiddle.values(session, VarRef.IntVar(0), rng).take(3).toList()
        assertEquals(listOf(Long.MIN_VALUE + 1, Long.MIN_VALUE + 2, Long.MIN_VALUE), values)
    }
}
