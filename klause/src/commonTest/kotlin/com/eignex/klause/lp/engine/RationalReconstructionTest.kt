package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
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
    fun `a vector holding an unreconstructable entry is declined whole`() {
        assertNull(reconstructIntegerVector(doubleArrayOf(0.5, kotlin.math.PI), maxDenominator = 1_000L))
    }

    @Test
    fun `unchanged coprime denominators still obey the whole vector limit`() {
        val values = listOf(3, 5).map { BigFraction.of(BIG_ONE, bigIntOf(it)) }

        val result = reconstructExactVector(values, bigIntOf(10), ReconstructionMeter())

        assertNull(result)
    }

    @Test
    fun `whole vector restarts when its common denominator leaves Long`() {
        val values = listOf(
            4_294_967_291L,
            4_294_967_279L,
        ).map { BigFraction.of(BIG_ONE, bigIntOf(it)) }
        val meter = ReconstructionMeter()

        val result = reconstructExactVector(values, BIG_ONE shl 80, meter)

        assertEquals(values, result)
        assertEquals(1, meter.vectorRestarts)
    }

    @Test
    fun `signed exact continued fractions retain the last admissible convergent`() {
        for (sign in listOf(-1L, 1L)) {
            val value = BigFraction.of(bigIntOf(31L * sign), bigIntOf(100))
            val result = reconstructExactVector(listOf(value), bigIntOf(10), ReconstructionMeter())
            assertEquals(listOf(BigFraction.of(bigIntOf(sign), bigIntOf(3))), result)
        }
    }

    @Test
    fun `common denominator overflow restarts before declining the whole vector`() {
        val values = listOf(
            4_294_967_291L,
            4_294_967_279L,
        ).map { BigFraction.of(BIG_ONE, bigIntOf(it)) }
        val meter = ReconstructionMeter()

        val result = reconstructExactVector(values, bigIntOf(Long.MAX_VALUE), meter)

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
