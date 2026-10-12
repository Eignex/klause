package com.eignex.klause.arithmetic.transcendental

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranscendentalArithmeticTest {
    @Test
    fun `special values have point enclosures`() {
        val arithmetic = TranscendentalArithmetic()
        val zero = RationalEnclosure.point(q(0))

        assertEquals(RationalEnclosure.point(q(1)), arithmetic.exp(zero, 4))
        assertEquals(zero, arithmetic.log(RationalEnclosure.point(q(1)), 4))
        assertEquals(zero, arithmetic.sin(zero, 4))
        assertEquals(RationalEnclosure.point(q(1)), arithmetic.cos(zero, 4))
    }

    @Test
    fun `exponential refinement lies inside exact coarse bounds`() {
        val arithmetic = TranscendentalArithmetic()
        val input = RationalEnclosure.point(q(1))

        val coarse = arithmetic.exp(input, 2)
        val refined = arithmetic.exp(input, 8)

        assertTrue(q(2) < refined.lower && refined.upper < q(3))
        assertTrue(coarse.lower <= refined.lower && refined.upper <= coarse.upper)
        assertTrue(refined.lower < refined.upper)
    }

    @Test
    fun `negative exponential reduction preserves reciprocal bounds`() {
        val arithmetic = TranscendentalArithmetic()
        val positive = arithmetic.exp(RationalEnclosure.point(q(8)), 4)

        val negative = arithmetic.exp(RationalEnclosure.point(q(-8)), 4)

        assertEquals(arithmetic.reciprocal(positive), negative)
    }

    @Test
    fun `log scale reduction preserves rational bounds near zero`() {
        val arithmetic = TranscendentalArithmetic()
        val input = RationalEnclosure.point(q(1) * q(256).reciprocal())

        val result = arithmetic.log(input, 6)

        assertTrue(q(-6) < result.lower && result.upper < q(-5))
    }

    @Test
    fun `log refuses nonpositive and crossing domains`() {
        val arithmetic = TranscendentalArithmetic()

        assertFailsWith<IllegalArgumentException> { arithmetic.log(RationalEnclosure.point(q(0)), 4) }
        assertFailsWith<IllegalArgumentException> { arithmetic.log(RationalEnclosure(q(-1), q(1)), 4) }
    }

    @Test
    fun `pi enclosure refines without floating constants`() {
        val arithmetic = TranscendentalArithmetic()
        val coarse = arithmetic.pi(2)

        val refined = arithmetic.pi(8)

        assertTrue(q(3) < refined.lower && refined.upper < q(22) * q(7).reciprocal())
        assertTrue(coarse.lower <= refined.lower && refined.upper <= coarse.upper)
    }

    @Test
    fun `range reduction proves a large argument has negative sine`() {
        val arithmetic = TranscendentalArithmetic()

        val result = arithmetic.sin(RationalEnclosure.point(q(100)), 12)

        assertTrue(result.lower < q(0) && result.upper < q(0))
    }

    @Test
    fun `trigonometric boxes keep interior extrema`() {
        val arithmetic = TranscendentalArithmetic()

        val result = arithmetic.cos(RationalEnclosure(q(-1), q(1)), 4)

        assertTrue(result.contains(q(1)))
    }

    @Test
    fun `strict rational separation does not collapse a near zero sine`() {
        val arithmetic = TranscendentalArithmetic()
        val input = q(1) * q(1000000).reciprocal()

        val result = arithmetic.sin(RationalEnclosure.point(input), 6)

        assertTrue(result.lower > q(0) && result.upper <= input + input)
        assertTrue(!result.isPoint)
    }

    @Test
    fun `cancelled arithmetic publishes no enclosure`() {
        val arithmetic = TranscendentalArithmetic(cancellation = Cancellation { true })

        assertFailsWith<TranscendentalInterrupted> { arithmetic.exp(RationalEnclosure.point(q(0)), 4) }
    }

    @Test
    fun `work cap is shared across refinements`() {
        val arithmetic = TranscendentalArithmetic(maximumWork = 2)
        arithmetic.exp(RationalEnclosure.point(q(0)), 4)

        assertFailsWith<TranscendentalResourceLimit> { arithmetic.pi(4) }
    }

    @Test
    fun `representation cap declines before a large squaring`() {
        val arithmetic = TranscendentalArithmetic(maximumBits = 32)

        assertFailsWith<TranscendentalResourceLimit> { arithmetic.exp(RationalEnclosure.point(q(100)), 4) }
    }

    private fun q(value: Long): BigFraction = BigFraction.ofLong(value)
}
