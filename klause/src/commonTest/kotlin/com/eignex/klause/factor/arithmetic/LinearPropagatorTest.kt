package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertTrue

class LinearPropagatorTest {
    @Test
    fun `linear disequalities remove targets outside the 32 bit range`() {
        for (target in listOf(Int.MIN_VALUE.toLong() - 1L, Int.MAX_VALUE.toLong() + 1L)) {
            val session = PropagationSession(
                Problem(
                    0,
                    1,
                    arrayOf(IntDomain(target - 1L, target + 1L)),
                    arrayOf(Linear(longArrayOf(1L), intArrayOf(0), LinearOp.NE, target)),
                ),
            )

            assertTrue(target !in session.intDomain(0))
        }
    }

    @Test
    fun `linear disequalities preserve values when the quotient exceeds Long`() {
        val session = PropagationSession(
            Problem(
                0,
                1,
                arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 1L)),
                arrayOf(Linear(longArrayOf(-1L), intArrayOf(0), LinearOp.NE, Long.MIN_VALUE)),
            ),
        )

        assertTrue(Long.MIN_VALUE in session.intDomain(0))
    }
}
