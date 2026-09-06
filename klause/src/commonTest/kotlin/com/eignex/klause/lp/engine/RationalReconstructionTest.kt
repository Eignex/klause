package com.eignex.klause.lp.engine

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

    private fun assertNotNullRational(r: Rational?): Rational {
        assertTrue(r != null, "expected a reconstruction")
        return r
    }

    private fun assertNotNullVector(v: LongArray?): LongArray {
        assertTrue(v != null, "expected a reconstruction")
        return v
    }
}
