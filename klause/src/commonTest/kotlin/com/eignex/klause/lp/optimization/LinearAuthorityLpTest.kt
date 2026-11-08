package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinearAuthorityLpTest {
    @Test
    fun `advice outside a present side should use its exact endpoint`() {
        val lower = ExactLpSide(ExactLpNumber.of(0L))
        val upper = ExactLpSide(ExactLpNumber.of(1L))
        for (strict in listOf(false, true)) {
            val bounds = ExactLpBounds(lower.copy(strict = strict), upper.copy(strict = strict))

            assertEquals(BigFraction.ZERO, bounds.projectGuidance(-Double.MIN_VALUE))
            assertEquals(BigFraction.ONE, bounds.projectGuidance(1.0000000000000002))
        }
    }

    @Test
    fun `rounded endpoint advice should retain the exact rational endpoint`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val twoThirds = third + third
        val bounds = ExactLpBounds(ExactLpSide(ExactLpNumber.of(third)), ExactLpSide(ExactLpNumber.of(twoThirds)))

        assertEquals(third, bounds.projectGuidance(third.toDouble()))
        assertEquals(twoThirds, bounds.projectGuidance(twoThirds.toDouble()))
    }

    @Test
    fun `interior advice should retain its represented value`() {
        val bounds = ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)), ExactLpSide(ExactLpNumber.of(1L)))

        val point = bounds.projectGuidance(0.5)

        assertEquals(BigFraction.ofLong(2).reciprocal(), point)
    }

    @Test
    fun `an absent side should leave advice open in that direction`() {
        val side = ExactLpSide(ExactLpNumber.of(0L))
        for ((bounds, value) in listOf(
            ExactLpBounds(lower = side) to 2.0,
            ExactLpBounds(upper = side) to -2.0,
            ExactLpBounds() to 2.0,
            ExactLpBounds() to -2.0,
        )) {
            assertEquals(BigFraction.ofLong(value.toLong()), bounds.projectGuidance(value))
        }
    }

    @Test
    fun `nonfinite advice should remain unavailable for every bound shape`() {
        val side = ExactLpSide(ExactLpNumber.of(0L))
        for (bounds in listOf(ExactLpBounds(), ExactLpBounds(lower = side),
            ExactLpBounds(upper = side), ExactLpBounds(side, side))) {
            for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
                assertNull(bounds.projectGuidance(value))
            }
        }
    }

    @Test
    fun `finite advice for a fixed coordinate should use its exact value`() {
        val side = ExactLpSide(ExactLpNumber.of(1L))
        val bounds = ExactLpBounds(side, side)

        for (value in listOf(-2.0, 0.0, 1.0, 2.0)) {
            assertEquals(BigFraction.ONE, bounds.projectGuidance(value))
        }
    }
}
