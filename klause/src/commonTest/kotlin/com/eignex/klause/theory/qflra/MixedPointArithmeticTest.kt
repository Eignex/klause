package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MixedPointArithmeticTest {
    @Test
    fun `prioritized row checks should preserve complete strict source truth`() {
        for (strict in listOf(false, true)) for (firstRow in 0..2) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE),
                ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ONE, strict),
            )
            val cases = listOf(
                listOf(BigFraction.ZERO, BigFraction.ZERO) to -1,
                listOf(BigFraction.MINUS_ONE, BigFraction.ZERO) to 0,
                listOf(BigFraction.ONE + BigFraction.ONE, BigFraction.ZERO) to 1,
                listOf(BigFraction.ZERO, BigFraction.ONE + BigFraction.ONE) to 2,
                listOf(BigFraction.ZERO, BigFraction.ONE) to if (strict) 2 else -1,
            )
            for ((point, expectedViolation) in cases) {
                var violation = -1

                val accepted = MixedPointArithmetic(Cancellation.Never).satisfies(point, rows, firstRow) {
                    violation = it
                }

                assertEquals(expectedViolation < 0, accepted)
                assertEquals(expectedViolation, violation)
            }
        }
    }

    @Test
    fun `prioritizing a rejecting row should avoid unrelated prefix work`() {
        val rows = List(32) {
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE)
        } + ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ZERO)
        val point = listOf(BigFraction.ZERO, BigFraction.ONE)
        val charges = listOf(0, rows.lastIndex).map { firstRow ->
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = false
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }

            val accepted = MixedPointArithmetic(stop).satisfies(point, rows, firstRow)

            assertFalse(accepted)
            charged
        }

        assertTrue(charges[1] < charges[0])
    }

    @Test
    fun `prioritized row checks should admit every point scalar`() {
        val point = listOf(BigFraction.ZERO, BigFraction.of(BIG_ONE shl 4096, BIG_ONE))
        val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE))
        var violations = 0

        val accepted = MixedPointArithmetic(Cancellation.Never).satisfies(point, rows, firstRow = 0) {
            violations++
        }

        assertFalse(accepted)
        assertEquals(0, violations)
    }

    @Test
    fun `cancelled prioritized row checks should withhold acceptance`() {
        val point = listOf(BigFraction.ZERO)
        val rows = List(2) {
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE)
        }
        var polls = 0
        val stop = Cancellation { ++polls >= 3 }
        var violations = 0

        val accepted = MixedPointArithmetic(stop).satisfies(point, rows, firstRow = 1) { violations++ }

        assertFalse(accepted)
        assertEquals(0, violations)
    }
}
