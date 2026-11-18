package com.eignex.klause.solver.objective

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.SemanticRational
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptimalityGapTest {
    @Test
    fun `algebraic gap closure should use absolute and relative source units in either direction`() {
        val arithmetic = AlgebraicArithmetic()
        val policy = OptimalityTolerance(SemanticRational.ONE,
            SemanticRational.of(bigIntOf(1), bigIntOf(10)))
        for (sense in ObjectiveSense.entries) {
            val sign = if (sense == ObjectiveSense.MINIMIZE) 1L else -1L
            val incumbent = arithmetic.rational(BigFraction.ofLong(-100 * sign))
            val near = arithmetic.rational(BigFraction.ofLong(-110 * sign))
            val far = arithmetic.rational(BigFraction.ofLong(-111 * sign))

            assertTrue(policy.closesDeclaredGap(incumbent, near, sense, arithmetic))
            assertFalse(policy.closesDeclaredGap(incumbent, far, sense, arithmetic))
            assertFalse(OptimalityTolerance().closesDeclaredGap(incumbent, incumbent, sense, arithmetic))
        }
    }

    private val mps = OptimalityTolerance(
        SemanticRational.of(bigIntOf(1), bigIntOf(10_000_000)),
        SemanticRational.of(bigIntOf(1), bigIntOf(10_000)),
    )

    @Test
    fun `a zero incumbent should close only within the absolute floor`() {
        val gap = OptimalityGap(mps)

        assertTrue(gap.closes(0.0, -0.9e-7))
        assertFalse(gap.closes(0.0, -1.1e-7))
    }

    @Test
    fun `a negative incumbent should be measured by its magnitude`() {
        val gap = OptimalityGap(mps)

        assertTrue(gap.closes(-1000.0, -1000.09))
        assertFalse(gap.closes(-1000.0, -1000.11))
    }

    @Test
    fun `working values should read in source units through the scale and offset`() {
        val gap = OptimalityGap(mps, scale = BigFraction.ofLong(2), offset = BigFraction.ofLong(-1000))

        assertTrue(gap.closes(1000.0, 999.96))
        assertFalse(gap.closes(1000.0, 999.94))
    }

    @Test
    fun `an integral objective should round a proved bound up before closing the gap`() {
        val integral = OptimalityGap(mps, integral = true)
        val continuous = OptimalityGap(mps)

        assertTrue(integral.closes(10001.0, 9999.5))
        assertFalse(continuous.closes(10001.0, 9999.5))
    }

    @Test
    fun `an infinite bound should never close the gap`() {
        val gap = OptimalityGap(mps)

        assertFalse(gap.closes(1.0, Double.NEGATIVE_INFINITY))
        assertFalse(gap.closes(Double.POSITIVE_INFINITY, 0.0))
    }

    @Test
    fun `the cutoff should lie at or below the incumbent and close its gap`() {
        val gap = OptimalityGap(mps)

        val cutoff = gap.cutoff(1000.0)

        assertTrue(cutoff < 1000.0)
        assertTrue(gap.closes(1000.0, cutoff))
    }

    @Test
    fun `an integral objective whose gap admits no integer below the incumbent should keep the incumbent as cutoff`() {
        val gap = OptimalityGap(mps, integral = true)

        assertEquals(100.0, gap.cutoff(100.0))
        assertNull(gap.exhaustedBound(100.0))
    }

    @Test
    fun `an exhausted search should report the cutoff bound unless a known bound reaches the incumbent`() {
        val gap = OptimalityGap(mps, integral = true)

        assertEquals(10000.0, gap.exhaustedBound(10001.0))
        assertNull(gap.exhaustedBound(10001.0, known = 10000.5))
    }

    @Test
    fun `an exact tolerance should close only a bound at the incumbent`() {
        val exact = OptimalityTolerance()

        assertTrue(exact.closesGap(BigFraction.ONE, BigFraction.ONE))
        assertFalse(exact.closesGap(BigFraction.ONE, BigFraction.ONE - BigFraction.ofLong(1_000_000_000).reciprocal()))
        assertFailsWith<IllegalArgumentException> { OptimalityGap(exact) }
    }
}
