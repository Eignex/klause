package com.eignex.klause.lp.optimization

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearPrimalRecoveryTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)

    @Test
    fun `reconstruction should preserve the exact integer assignment`() {
        val third = BigFraction.ONE * BigFraction.ofLong(3).reciprocal()
        val wide = BigFraction.ofLong(9007199254740993L)
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), third),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), third.negated()),
        )

        val point = recoverLinearPrimal(listOf(real, integer), listOf(checkNotNull(BigFraction.ofDouble(1.0 / 3)), wide),
            rows, emptySet(), Cancellation.Never, complete = { _, _, _ -> error("completion unnecessary") },
            observe = { _, _ -> })

        assertEquals(listOf(third, wide), point)
    }

    @Test
    fun `fractional integers should not be rounded by residual recovery`() {
        val point = recoverLinearPrimal(listOf(real, integer), listOf(BigFraction.ZERO,
            BigFraction.ofLong(2).reciprocal()), emptyList(), emptySet(), Cancellation.Never,
            complete = { _, _, _ -> error("fractional assignment") }, observe = { _, _ -> })

        assertNull(point)
    }

    @Test
    fun `inactive source rows should not restrict continuous recovery`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.MINUS_ONE),
        )

        val point = recoverLinearPrimal(listOf(real), listOf(BigFraction.ZERO), rows, setOf(0), Cancellation.Never,
            complete = { _, _, _ -> error("inactive row") }, observe = { _, _ -> })

        assertEquals(listOf(BigFraction.ZERO), point)
    }

    @Test
    fun `recovery limits and cancellation should withhold candidate points`() {
        for ((limits, token) in listOf(
            LinearPrimalRecoveryLimits(work = 0L) to Cancellation.Never,
            LinearPrimalRecoveryLimits(allocation = 0L) to Cancellation.Never,
            LinearPrimalRecoveryLimits() to Cancellation { true },
        )) {
            val point = recoverLinearPrimal(listOf(real), listOf(BigFraction.ZERO), emptyList(), emptySet(), token,
                complete = { _, _, _ -> error("stopped recovery") }, observe = { _, _ -> }, limits = limits)

            assertNull(point)
        }
    }

    @Test
    fun `a completed residual should still satisfy every selected strict row`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true),
        )

        for (value in listOf(BigFraction.ZERO, BigFraction.ONE)) {
            val point = recoverLinearPrimal(listOf(real), listOf(BigFraction.ZERO), rows, emptySet(), Cancellation.Never,
                complete = { _, _, _ -> listOf(value) }, observe = { _, _ -> })

            if (value.isZero) assertNull(point) else assertEquals(listOf(value), assertNotNull(point))
        }
    }
}
