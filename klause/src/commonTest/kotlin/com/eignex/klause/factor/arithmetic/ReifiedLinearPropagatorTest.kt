package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReifiedLinearPropagatorTest {
    @Test
    fun `reified equalities retain valid targets outside the 32 bit range`() {
        val targets = listOf(
            Int.MIN_VALUE.toLong() - 1L,
            Int.MAX_VALUE.toLong() + 1L,
            Long.MIN_VALUE + 1L,
            Long.MAX_VALUE - 1L,
        )
        for (target in targets) {
            val session = PropagationSession(
                Problem(
                    1,
                    1,
                    arrayOf(IntDomain(target - 1L, target + 1L)),
                    arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.EQ, target)),
                ),
            )
            assertNull(session.boolValue(0))

            assertIs<PropagationResult.Implied>(session.pinBool(0, true))

            assertEquals(target, session.intDomain(0).min)
            assertEquals(target, session.intDomain(0).max)
        }
    }

    @Test
    fun `a negated reified equality removes its target outside the 32 bit range`() {
        for (target in listOf(Int.MIN_VALUE.toLong() - 1L, Int.MAX_VALUE.toLong() + 1L)) {
            val session = PropagationSession(
                Problem(
                    1,
                    1,
                    arrayOf(IntDomain(target - 1L, target + 1L)),
                    arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.EQ, target)),
                ),
            )

            assertIs<PropagationResult.Implied>(session.pinBool(0, false))

            assertTrue(target !in session.intDomain(0))
        }
    }

    @Test
    fun `a carved equality target outside the 32 bit range keeps its premise`() {
        for (target in listOf(Int.MIN_VALUE.toLong() - 1L, Int.MAX_VALUE.toLong() + 1L)) {
            val problem = Problem(
                1,
                1,
                arrayOf(IntDomain(target - 1L, target + 1L)),
                arrayOf(ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.EQ, target)),
            )

            PropagationReasonOracle.assertReasonsImply(problem, "64 bit equality hole $target") { state ->
                state.excludeIntValue(0, target)
            }
        }
    }

    @Test
    fun `an overflowing equality quotient refutes its indicator`() {
        val session = PropagationSession(
            Problem(
                1,
                1,
                arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 1L)),
                arrayOf(ReifiedLinear(0, longArrayOf(-1L), intArrayOf(0), LinearOp.EQ, Long.MIN_VALUE)),
            ),
        )

        assertEquals(false, session.boolValue(0))
    }
}
