package com.eignex.klause.simplex.exact

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BigFractionTest {
    @Test
    fun `fraction sums and differences match the normalized cross product`() {
        for (a in fractionSamples) {
            for (b in fractionSamples) {
                assertEquals(BigFraction.of(a.num * b.den + b.num * a.den, a.den * b.den), a + b, "$a + $b")
                assertEquals(BigFraction.of(a.num * b.den - b.num * a.den, a.den * b.den), a - b, "$a - $b")
            }
        }
    }

    @Test
    fun `fraction equality and hashing agree with normalized values`() {
        for (a in fractionSamples) {
            val copy = BigFraction.of(a.num * bigIntOf(7), a.den * bigIntOf(7))
            assertEquals(a, copy)
            assertEquals(a.hashCode(), copy.hashCode())
            for (b in fractionSamples) {
                val equal = (a.num * b.den).compareTo(b.num * a.den) == 0
                assertEquals(equal, a == b, "$a vs $b")
            }
        }
    }

    @Test
    fun `ofDouble is the exact rational of the stored double`() {
        // 0.5 is exactly 1/2; 0.1 is exactly 3602879701896397/2^55, NOT 1/10.
        assertEquals("1/2", BigFraction.ofDouble(0.5).toString())
        assertEquals("3602879701896397/36028797018963968", BigFraction.ofDouble(0.1).toString())
    }

    @Test
    fun `fraction construction reduces signs units and nontrivial common factors`() {
        val samples = listOf(
            longArrayOf(0, -7, 0, 1),
            longArrayOf(17, 1, 17, 1),
            longArrayOf(17, -1, -17, 1),
            longArrayOf(1, 17, 1, 17),
            longArrayOf(-1, -17, 1, 17),
            longArrayOf(7, 11, 7, 11),
            longArrayOf(-7, 11, -7, 11),
            longArrayOf(21, -33, -7, 11),
            longArrayOf(Long.MIN_VALUE, 2, Long.MIN_VALUE / 2, 1),
        )
        for (sample in samples) {
            val value = BigFraction.of(bigIntOf(sample[0]), bigIntOf(sample[1]))

            assertEquals(bigIntOf(sample[2]), value.num)
            assertEquals(bigIntOf(sample[3]), value.den)
        }
    }

    @Test
    fun `IEEE conversion preserves canonical fractions across exponent boundaries`() {
        val samples = listOf(
            Triple(0.0, bigIntOf(0), BIG_ONE),
            Triple(-0.0, bigIntOf(0), BIG_ONE),
            Triple(0.5, BIG_ONE, bigIntOf(2)),
            Triple(-1.0, -BIG_ONE, BIG_ONE),
            Triple(0.1, bigIntOf(3602879701896397L), BIG_ONE shl 55),
            Triple(Double.MIN_VALUE, BIG_ONE, BIG_ONE shl 1074),
            Triple(-Double.MIN_VALUE, -BIG_ONE, BIG_ONE shl 1074),
            Triple(Double.fromBits(2L), BIG_ONE, BIG_ONE shl 1073),
            Triple(Double.fromBits(3L), bigIntOf(3), BIG_ONE shl 1074),
            Triple(Double.fromBits(0x0010000000000000L), BIG_ONE, BIG_ONE shl 1022),
            Triple(Double.fromBits(0x000fffffffffffffL), bigIntOf(0x000fffffffffffffL), BIG_ONE shl 1074),
            Triple(Double.MAX_VALUE, bigIntOf(0x001fffffffffffffL) shl 971, BIG_ONE),
            Triple(-Double.MAX_VALUE, -(bigIntOf(0x001fffffffffffffL) shl 971), BIG_ONE),
        )
        for ((input, numerator, denominator) in samples) {
            val value = assertNotNull(BigFraction.ofDouble(input))

            assertEquals(numerator, value.num, "$input numerator")
            assertEquals(denominator, value.den, "$input denominator")
        }
    }

    @Test
    fun `IEEE conversion declines every nonfinite input`() {
        for (input in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(BigFraction.ofDouble(input))
        }
    }

    @Test
    fun `integer ceiling rounds positive and negative fractions upward`() {
        for ((numerator, denominator, expected) in listOf(
            Triple(0L, 3L, 0L),
            Triple(4L, 2L, 2L),
            Triple(3L, 2L, 2L),
            Triple(-3L, 2L, -1L),
            Triple(-4L, 2L, -2L),
        )) {
            val fraction = BigFraction.of(bigIntOf(numerator), bigIntOf(denominator))

            assertEquals(bigIntOf(expected), fraction.ceilInteger())
        }
    }

    private val fractionSamples = listOf(
        BigFraction.ZERO, BigFraction.ONE, BigFraction.MINUS_ONE, q(-6, 1), q(1, 2), q(-3, 4), q(5, 12),
        q(7, 18), q(6, 35), q(-10, 21), q(1, 1024), checkNotNull(BigFraction.ofDouble(0.1)),
        BigFraction.of(-(BIG_ONE shl 70), bigIntOf(3)),
    )

    private fun q(num: Long, den: Long) = BigFraction.of(bigIntOf(num), bigIntOf(den))
}
