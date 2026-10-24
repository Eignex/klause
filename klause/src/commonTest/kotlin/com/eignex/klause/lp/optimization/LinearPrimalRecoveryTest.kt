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

        val guess = listOf(checkNotNull(BigFraction.ofDouble(1.0 / 3)), wide)
        val point = recoverLinearPrimal(listOf(real, integer), guess, rows, emptySet(), Cancellation.Never,
            complete = { _, _, _ -> error("completion unnecessary") },
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
    fun `reconstruction should retain exact real source endpoints`() {
        val coordinates = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val third = BigFraction.ofLong(3).reciprocal()
        val wide = BigFraction.ofLong(9007199254740993L)
        val dyadic = checkNotNull(BigFraction.ofDouble(0.1))
        val rows = listOf(third, wide, dyadic).flatMapIndexed { id, value -> listOf(
            ExactRationalInequality(intArrayOf(id), listOf(BigFraction.ONE), value),
            ExactRationalInequality(intArrayOf(id), listOf(BigFraction.MINUS_ONE), value.negated()),
        ) }

        val guess = listOf(checkNotNull(BigFraction.ofDouble(1.0 / 3)), wide, dyadic)
        val point = recoverLinearPrimal(coordinates, guess, rows, emptySet(), Cancellation.Never,
            complete = { _, _, _ -> null }, observe = { _, _ -> })

        assertEquals(listOf(third, wide, dyadic), point)
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
            val point = recoverLinearPrimal(listOf(real), listOf(BigFraction.ZERO), rows, emptySet(),
                Cancellation.Never,
                complete = { _, _, _ -> listOf(value) }, observe = { _, _ -> })

            if (value.isZero) assertNull(point) else assertEquals(listOf(value), assertNotNull(point))
        }
    }

    @Test
    fun `an unfinished recovery should retain completed continuous components`() {
        val other = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val rows = (0..1).map {
            ExactRationalInequality(intArrayOf(it), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO, strict = true)
        }
        val progress = LinearPrimalRecoveryProgress()
        val stages = mutableMapOf<String, Long>()
        var calls = 0
        val complete: (List<ExactRationalInequality>, Int, Cancellation) -> List<BigFraction>? = { _, _, _ ->
            calls++
            if (calls == 2) null else listOf(BigFraction.ONE)
        }
        val observe: (String, Long) -> Unit = { stage, count -> stages[stage] = (stages[stage] ?: 0L) + count }

        val first = recoverLinearPrimal(listOf(real, other), List(2) { BigFraction.ZERO }, rows, emptySet(),
            Cancellation.Never, complete, observe, progress = progress)
        val resumed = recoverLinearPrimal(listOf(real, other), List(2) { BigFraction.ZERO }, rows, emptySet(),
            Cancellation.Never, complete, observe, progress = progress)

        assertNull(first)
        assertEquals(listOf(BigFraction.ONE, BigFraction.ONE), resumed)
        assertEquals(3, calls)
        assertEquals(1L, stages["PrimalRecoveryComponentReused"])
        assertEquals(progress.work, stages["PrimalRecoveryWork"])
    }

    @Test
    fun `connected residual rows should preserve source column order`() {
        val coordinates = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val rows = listOf(2, 1).map {
            ExactRationalInequality(intArrayOf(0, it), List(2) { BigFraction.MINUS_ONE },
                BigFraction.ZERO, strict = true)
        }

        val point = recoverLinearPrimal(coordinates, List(3) { BigFraction.ZERO }, rows, emptySet(), Cancellation.Never,
            complete = { _, columns, _ -> List(columns) { BigFraction.ONE } }, observe = { _, _ -> })

        assertEquals(List(3) { BigFraction.ONE }, point)
    }
}
