package com.eignex.klause.lp.optimization

import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.collections.IntIndexedCollection
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearPrimalRecoveryTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)

    @Test
    fun `small recovery offers should complete without rebuilding prepared rows`() {
        val coordinates = (0..1).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val guess = List(2) { BigFraction.ZERO }
        val rows = List(80) {
            ExactRationalInequality(intArrayOf(it % 2), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)
        }
        val limits = LinearPrimalRecoveryLimits(work = 20_000L, allocation = 1_000_000L)
        val uninterrupted = LinearPrimalRecoveryProgress(retainPreparation = true)
        val expected = recoverLinearPrimal(coordinates, guess, rows, emptySet(), ExecutionControl.Never,
            complete = { _, columns, _ -> List(columns.size) { BigFraction.ONE } }, observe = { _, _ -> },
            limits = limits, progress = uninterrupted)
        val sliced = LinearPrimalRecoveryProgress(retainPreparation = true)
        var offerWork = 0L
        val token = ExecutionControl({ offerWork >= 100L }, WorkMeter { offerWork += it })
        var actual: List<BigFraction>? = null

        repeat(256) {
            if (actual == null) {
                offerWork = 0L
                actual = recoverLinearPrimal(coordinates, guess, rows, emptySet(), token,
                    complete = { _, columns, _ -> List(columns.size) { BigFraction.ONE } }, observe = { _, _ -> },
                    limits = limits, progress = sliced)
            }
        }

        assertEquals(List(2) { BigFraction.ONE }, assertNotNull(expected))
        assertEquals(expected, assertNotNull(actual))
        assertEquals(uninterrupted.allocation, sliced.allocation)
        assertEquals(uninterrupted.work, sliced.work)
    }

    @Test
    fun `retained recovery should refuse changed immutable inputs`() {
        val coordinates = listOf(real)
        val point = listOf(BigFraction.ZERO)
        val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))
        val progress = LinearPrimalRecoveryProgress(retainPreparation = true)
        val inactive = emptySet<Int>()

        assertNull(recoverLinearPrimal(coordinates, point, rows, inactive, ExecutionControl { true },
            complete = { _, _, _ -> error("cancelled preparation") }, observe = { _, _ -> }, progress = progress))
        assertNull(recoverLinearPrimal(coordinates, listOf(BigFraction.ONE), rows, inactive, ExecutionControl.Never,
            complete = { _, _, _ -> error("changed input") }, observe = { _, _ -> }, progress = progress))
        assertNull(recoverLinearPrimal(coordinates, point, rows, inactive, ExecutionControl.Never,
            complete = { _, _, _ -> error("retired input") }, observe = { _, _ -> }, progress = progress))
    }

    @Test
    fun `retained recovery limits should remain exhausted across offers`() {
        val coordinates = listOf(real)
        val point = listOf(BigFraction.ZERO)
        val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))
        for (limits in listOf(LinearPrimalRecoveryLimits(work = 0L),
            LinearPrimalRecoveryLimits(allocation = 0L), LinearPrimalRecoveryLimits(entries = 0L))) {
            val progress = LinearPrimalRecoveryProgress(retainPreparation = true)
            val complete = { _: List<ExactRationalInequality>, _: IntIndexedCollection, _: ExecutionControl ->
                error("exhausted preparation")
            }

            assertNull(recoverLinearPrimal(coordinates, point, rows, emptySet(), ExecutionControl.Never, complete,
                observe = { _, _ -> }, limits = limits, progress = progress))
            val spent = progress.work to progress.allocation
            assertNull(recoverLinearPrimal(coordinates, point, rows, emptySet(), ExecutionControl.Never, complete,
                observe = { _, _ -> }, limits = limits, progress = progress))

            assertEquals(spent, progress.work to progress.allocation)
        }
    }

    @Test
    fun `retained recovery should resume its active component without repeating completed components`() {
        val coordinates = (0..1).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val guess = List(2) { BigFraction.ZERO }
        val rows = (0..1).map {
            ExactRationalInequality(intArrayOf(it), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)
        }
        val progress = LinearPrimalRecoveryProgress(retainPreparation = true)
        var paused = false
        var calls = 0
        val token = ExecutionControl { paused }
        val complete: (List<ExactRationalInequality>, IntIndexedCollection, ExecutionControl) -> List<BigFraction>? =
            { _, _, _ ->
                calls++
                if (calls == 2) {
                    paused = true
                    null
                } else listOf(BigFraction.ONE)
            }

        assertNull(recoverLinearPrimal(coordinates, guess, rows, emptySet(), token, complete,
            observe = { _, _ -> }, progress = progress))
        paused = false
        val resumed = recoverLinearPrimal(coordinates, guess, rows, emptySet(), token, complete,
            observe = { _, _ -> }, progress = progress)

        assertEquals(List(2) { BigFraction.ONE }, assertNotNull(resumed))
        assertEquals(3, calls)
    }

    @Test
    fun `declined retained components should not acquire another completion attempt`() {
        val coordinates = listOf(real)
        val guess = listOf(BigFraction.ZERO)
        val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE))
        for (supplied in listOf(null, emptyList(), listOf(BigFraction.ZERO))) {
            val progress = LinearPrimalRecoveryProgress(retainPreparation = true)
            var calls = 0
            val complete = { _: List<ExactRationalInequality>, _: IntIndexedCollection, _: ExecutionControl ->
                calls++
                supplied
            }

            repeat(2) {
                assertNull(recoverLinearPrimal(coordinates, guess, rows, emptySet(), ExecutionControl.Never,
                    complete, observe = { _, _ -> }, progress = progress))
            }

            assertEquals(1, calls)
        }
    }

    @Test
    fun `an exhausted recovery allowance should retire a preceding component pause`() {
        val progress = LinearPrimalRecoveryProgress().apply { pending = true }

        val point = recoverLinearPrimal(listOf(real), listOf(BigFraction.ZERO), emptyList(), emptySet(),
            ExecutionControl.Never, complete = { _, _, _ -> error("exhausted recovery") }, observe = { _, _ -> },
            limits = LinearPrimalRecoveryLimits(work = 0L), progress = progress)

        assertNull(point)
        assertFalse(progress.pending)
    }

    @Test
    fun `reconstruction should preserve the exact integer assignment`() {
        val third = BigFraction.ONE * BigFraction.ofLong(3).reciprocal()
        val wide = BigFraction.ofLong(9007199254740993L)
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), third),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), third.negated()),
        )

        val guess = listOf(checkNotNull(BigFraction.ofDouble(1.0 / 3)), wide)
        val point = recoverLinearPrimal(listOf(real, integer), guess, rows, emptySet(), ExecutionControl.Never,
            complete = { _, _, _ -> error("completion unnecessary") },
            observe = { _, _ -> })

        assertEquals(listOf(third, wide), point)
    }

    @Test
    fun `fractional integers should not be rounded by residual recovery`() {
        val point = recoverLinearPrimal(listOf(real, integer), listOf(BigFraction.ZERO,
            BigFraction.ofLong(2).reciprocal()), emptyList(), emptySet(), ExecutionControl.Never,
            complete = { _, _, _ -> error("fractional assignment") }, observe = { _, _ -> })

        assertNull(point)
    }

    @Test
    fun `reconstruction should retain exact real source endpoints`() {
        val coordinates = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Real, it) }
        val third = BigFraction.ofLong(3).reciprocal()
        val wide = BigFraction.ofLong(9007199254740993L)
        val dyadic = checkNotNull(BigFraction.ofDouble(0.1))
        val rows = listOf(third, wide, dyadic).flatMapIndexed { id, value ->
            listOf(
                ExactRationalInequality(intArrayOf(id), listOf(BigFraction.ONE), value),
                ExactRationalInequality(intArrayOf(id), listOf(BigFraction.MINUS_ONE), value.negated()),
            )
        }

        val guess = listOf(checkNotNull(BigFraction.ofDouble(1.0 / 3)), wide, dyadic)
        val point = recoverLinearPrimal(coordinates, guess, rows, emptySet(), ExecutionControl.Never,
            complete = { _, _, _ -> null }, observe = { _, _ -> })

        assertEquals(listOf(third, wide, dyadic), point)
    }

    @Test
    fun `inactive source rows should not restrict continuous recovery`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.MINUS_ONE),
        )

        val point = recoverLinearPrimal(listOf(real), listOf(BigFraction.ZERO), rows, setOf(0), ExecutionControl.Never,
            complete = { _, _, _ -> error("inactive row") }, observe = { _, _ -> })

        assertEquals(listOf(BigFraction.ZERO), point)
    }

    @Test
    fun `recovery limits and cancellation should withhold candidate points`() {
        for ((limits, token) in listOf(
            LinearPrimalRecoveryLimits(work = 0L) to ExecutionControl.Never,
            LinearPrimalRecoveryLimits(allocation = 0L) to ExecutionControl.Never,
            LinearPrimalRecoveryLimits() to ExecutionControl { true },
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
                ExecutionControl.Never,
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
        val complete: (List<ExactRationalInequality>, IntIndexedCollection, ExecutionControl) -> List<BigFraction>? =
            { _, _, _ ->
                calls++
                if (calls == 2) null else listOf(BigFraction.ONE)
            }
        val observe: (String, Long) -> Unit = { stage, count -> stages[stage] = (stages[stage] ?: 0L) + count }

        val first = recoverLinearPrimal(listOf(real, other), List(2) { BigFraction.ZERO }, rows, emptySet(),
            ExecutionControl.Never, complete, observe, progress = progress)
        val resumed = recoverLinearPrimal(listOf(real, other), List(2) { BigFraction.ZERO }, rows, emptySet(),
            ExecutionControl.Never, complete, observe, progress = progress)

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

        val point = recoverLinearPrimal(coordinates, List(3) { BigFraction.ZERO }, rows, emptySet(),
            ExecutionControl.Never,
            complete = { _, columns, _ -> List(columns.size) { BigFraction.ofLong(it + 1L) } }, observe = { _, _ -> })

        assertEquals(List(3) { BigFraction.ofLong(it + 1L) }, point)
    }
}
