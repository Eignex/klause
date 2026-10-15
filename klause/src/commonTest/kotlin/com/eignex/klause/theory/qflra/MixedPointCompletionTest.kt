package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedPointCompletionTest {
    @Test
    fun `integer guesses admit exact strict real completion only after full row checking`() {
        val rows = listOf(
            exactColumnLower(0, BigFraction.ZERO),
            exactColumnUpper(1, BigFraction.ofLong(3)),
            exactColumnLower(2, BigFraction.ONE),
            exactColumnUpper(2, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1, 2),
                listOf(BigFraction.ONE, BigFraction.ONE.negated(), BigFraction.ONE), BigFraction.ZERO, strict = true),
        )

        val point = assertNotNull(completeMixedDifferencePoint(2, 1, doubleArrayOf(0.0, 0.0, 1.0),
            rows, Cancellation.Never))

        assertEquals(BigFraction.ONE, point[2])
        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `rounded integers cannot escape current branch bounds`() {
        val rows = listOf(exactColumnLower(1, BigFraction.ONE), exactColumnUpper(1, BigFraction.ONE))
        for (value in listOf(1.6, -0.6)) {
            val point = completeMixedDifferencePoint(1, 1, doubleArrayOf(0.0, value), rows, Cancellation.Never)

            assertNull(point)
        }
    }

    @Test
    fun `non difference residuals decline the integer guess`() {
        val rows = listOf(ExactRationalInequality(intArrayOf(0, 1),
            listOf(BigFraction.ONE, BigFraction.ONE), BigFraction.ONE))

        val point = completeMixedDifferencePoint(2, 1, doubleArrayOf(0.0, 0.0, 1.0), rows, Cancellation.Never)

        assertNull(point)
    }

    @Test
    fun `inconsistent real residuals do not produce a witness`() {
        val rows = listOf(exactColumnLower(0, BigFraction.ONE), exactColumnUpper(0, BigFraction.ZERO))

        val point = completeMixedDifferencePoint(1, 1, doubleArrayOf(0.0, 1.0), rows, Cancellation.Never)

        assertNull(point)
    }

    @Test
    fun `stopped completion retains caller work without publishing a point`() {
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged > 0L
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val point = completeMixedDifferencePoint(1, 1, doubleArrayOf(0.0, 1.0), emptyList(), stop)

        assertNull(point)
        assertTrue(charged > 0L)
    }
}
