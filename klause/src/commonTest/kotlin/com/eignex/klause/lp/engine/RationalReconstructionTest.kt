package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RationalReconstructionTest {

    @Test
    fun `recovers a simple rational a float only approximates`() {
        val r = assertNotNullRational(reconstructRational(1.0 / 3.0))

        assertEquals(1L, r.numerator)
        assertEquals(3L, r.denominator)
    }

    @Test
    fun `recovers the sign on the numerator`() {
        val r = assertNotNullRational(reconstructRational(-2.0 / 7.0))

        assertEquals(-2L, r.numerator)
        assertEquals(7L, r.denominator)
    }

    @Test
    fun `an exact integer reconstructs with denominator one`() {
        val r = assertNotNullRational(reconstructRational(-5.0))

        assertEquals(-5L, r.numerator)
        assertEquals(1L, r.denominator)
    }

    @Test
    fun `a value below tolerance reconstructs as exactly zero`() {
        val r = assertNotNullRational(reconstructRational(-7.8e-13))

        assertEquals(0L, r.numerator)
        assertEquals(1L, r.denominator)
    }

    @Test
    fun `an irrational is not a small rational and is declined`() {
        assertNull(reconstructRational(kotlin.math.PI, maxDenominator = 1_000L))
    }

    @Test
    fun `a denominator past the bound is declined`() {
        assertNull(reconstructRational(1.0 / 1_000_003.0, maxDenominator = 1_000L))
    }

    @Test
    fun `a vector clears its denominators to one integer scale`() {
        // 1/2, 1/3, 1 share denominator 6 -> 3, 2, 6.
        val v = assertNotNullVector(reconstructIntegerVector(doubleArrayOf(0.5, 1.0 / 3.0, 1.0)))

        assertEquals(listOf(3L, 2L, 6L), v.toList())
    }

    @Test
    fun `the cleared vector is exactly proportional to the input`() {
        val input = doubleArrayOf(0.25, -0.75, 2.0)
        val v = assertNotNullVector(reconstructIntegerVector(input))

        // Every entry scaled by the same positive factor, which is what leaves a ray a ray.
        val factor = v[0].toDouble() / input[0]
        assertTrue(factor > 0.0, "the common scale must stay positive")
        for (i in input.indices) {
            assertTrue(
                kotlin.math.abs(v[i].toDouble() - factor * input[i]) < 1e-6,
                "entry $i is not the same multiple: ${v[i]} vs ${factor * input[i]}",
            )
        }
    }

    @Test
    fun `a vector holding an unreconstructable entry is declined whole`() {
        assertNull(reconstructIntegerVector(doubleArrayOf(0.5, kotlin.math.PI), maxDenominator = 1_000L))
    }

    @Test
    fun `unchanged coprime denominators still obey the whole vector limit`() {
        val values = listOf(3, 5).map { BigFraction.of(BigInteger.ONE, BigInteger.fromInt(it)) }

        val result = reconstructExactVector(values, BigInteger.fromInt(10), ReconstructionMeter())

        assertNull(result)
    }

    @Test
    fun `whole vector restarts when its common denominator leaves Long`() {
        val values = listOf(
            4_294_967_291L,
            4_294_967_279L,
        ).map { BigFraction.of(BigInteger.ONE, BigInteger.fromLong(it)) }
        val meter = ReconstructionMeter()

        val result = reconstructExactVector(values, BigInteger.ONE shl 80, meter)

        assertEquals(values, result)
        assertEquals(1, meter.vectorRestarts)
    }

    @Test
    fun `signed exact continued fractions retain the last admissible convergent`() {
        for (sign in listOf(-1L, 1L)) {
            val value = BigFraction.of(BigInteger.fromLong(31L * sign), BigInteger.fromInt(100))
            val result = reconstructExactVector(listOf(value), BigInteger.fromInt(10), ReconstructionMeter())
            assertEquals(listOf(BigFraction.of(BigInteger.fromLong(sign), BigInteger.fromInt(3))), result)
        }
    }

    @Test
    fun `error correction makes the denominator bound nonincreasing`() {
        val violation = BigFraction.of(BigInteger.ONE, BigInteger.ONE shl 100)
        val correction = BigFraction.ofLong(2L)
        val next = BigFraction.of(BigInteger.fromInt(22), BigInteger.fromInt(10))

        val first = reconstructionDenominator(violation, correction, ReconstructionMeter())
        val second = reconstructionDenominator(violation, next, ReconstructionMeter())

        assertTrue(second < first)
        assertEquals(
            RECONSTRUCTION_FLOOR,
            reconstructionDenominator(BigFraction.ONE, correction, ReconstructionMeter()),
        )
        assertEquals(
            listOf(1, 2, 3, 4, 5, 7, 9),
            generateSequence(0, ::nextReconstructionRound).drop(1).take(7).toList(),
        )
    }

    @Test
    fun `common denominator overflow restarts before declining the whole vector`() {
        val values = listOf(
            4_294_967_291L,
            4_294_967_279L,
        ).map { BigFraction.of(BigInteger.ONE, BigInteger.fromLong(it)) }
        val meter = ReconstructionMeter()

        val result = reconstructExactVector(values, BigInteger.fromLong(Long.MAX_VALUE), meter)

        assertNull(result)
        assertEquals(1, meter.vectorRestarts)
    }

    private fun assertNotNullRational(r: Rational?): Rational {
        assertTrue(r != null, "expected a reconstruction")
        return r
    }

    private fun assertNotNullVector(v: LongArray?): LongArray {
        assertTrue(v != null, "expected a reconstruction")
        return v
    }
}
