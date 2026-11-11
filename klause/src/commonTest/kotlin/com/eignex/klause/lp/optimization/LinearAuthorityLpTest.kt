package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearAuthorityLpTest {
    @Test
    fun `numerical deduction work should reach source funding before phase publication`() {
        val funding = SourceLpFunding(100_000L, 64L * 1024L * 1024L).also { it.grant(100_000L) }
        val budget = SourceLpBudget(funding = funding, solveContext = { LpSolveContext.Numerical })
        var numericalWork = 0L
        var publishedWork = 0L
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-2)),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(3)),
        )

        val result = LinearAuthorityLp(budget, onPhase = {
            if (it.phase == LpSolvePhase.NUMERICAL_BOUND) {
                numericalWork += it.work
                publishedWork = budget.measuredFloatWork
            }
        }).use { it.solve(rows, listOf(BigFraction.ONE), BigFraction.ZERO, Cancellation.Never) }

        assertEquals(2.0, assertNotNull(result?.result?.numerical?.bound).value)
        assertTrue(numericalWork > 0L)
        assertEquals(numericalWork, publishedWork)
        assertTrue(funding.spent >= numericalWork)
    }

    @Test
    fun `cancelled numerical publication should retain its deduction work`() {
        val funding = SourceLpFunding(100_000L, 64L * 1024L * 1024L).also { it.grant(100_000L) }
        val budget = SourceLpBudget(funding = funding, solveContext = { LpSolveContext.Numerical })
        var cancelled = false
        var numericalWork = 0L
        var chargedWhenCancelled = 0L
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-2)),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(3)),
        )

        val result = LinearAuthorityLp(budget, onPhase = {
            if (it.phase == LpSolvePhase.NUMERICAL_BOUND) {
                numericalWork += it.work
                chargedWhenCancelled = budget.measuredFloatWork
                cancelled = true
            }
        }).use { it.solve(rows, listOf(BigFraction.ONE), BigFraction.ZERO, Cancellation { cancelled }) }

        assertNull(result)
        assertTrue(numericalWork > 0L)
        assertEquals(numericalWork, chargedWhenCancelled)
        assertTrue(budget.measuredFloatWork >= chargedWhenCancelled)
    }

    @Test
    fun `completed floating separation should use only the source coordinate prefix`() {
        val funding = SourceLpFunding(1024L, 4096L).also { it.grant(1024L) }
        val lp = LinearAuthorityLp(SourceLpBudget(funding = funding))
        val result = FloatLpResult(Basis(intArrayOf(), emptyArray()), 0.0, doubleArrayOf(),
            doubleArrayOf(0.5, -1.0, Double.NaN))

        val point = lp.separationPoint(result, 2, Cancellation.Never)

        assertEquals(listOf(BigFraction.ofLong(2).reciprocal(), BigFraction.MINUS_ONE), point)
        assertTrue(funding.spent > 0L)
        assertTrue(funding.allocated > 0L)
        lp.close()
    }

    @Test
    fun `unfinished floating solves should supply no separation point`() {
        val funding = SourceLpFunding(1024L, 4096L).also { it.grant(1024L) }
        val lp = LinearAuthorityLp(SourceLpBudget(funding = funding))
        val result = FloatLpResult(Basis(intArrayOf(), emptyArray()), 0.0, doubleArrayOf(),
            doubleArrayOf(0.5), optimal = false)

        val point = lp.separationPoint(result, 1, Cancellation.Never)

        assertNull(point)
        lp.close()
    }

    @Test
    fun `nonfinite source coordinates should withhold the entire separation point`() {
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val funding = SourceLpFunding(1024L, 4096L).also { it.grant(1024L) }
            val lp = LinearAuthorityLp(SourceLpBudget(funding = funding))
            val result = FloatLpResult(Basis(intArrayOf(), emptyArray()), 0.0, doubleArrayOf(),
                doubleArrayOf(0.5, value))

            val point = lp.separationPoint(result, 2, Cancellation.Never)

            assertNull(point)
            lp.close()
        }
    }

    @Test
    fun `floating separation should require containing work and allocation funding`() {
        for (funding in listOf(null, SourceLpFunding(0L, 4096L), SourceLpFunding(1024L, 0L))) {
            funding?.grant(1024L)
            val lp = LinearAuthorityLp(SourceLpBudget(funding = funding))
            val result = FloatLpResult(Basis(intArrayOf(), emptyArray()), 0.0, doubleArrayOf(), doubleArrayOf(0.5))

            val point = lp.separationPoint(result, 1, Cancellation.Never)

            assertNull(point)
            lp.close()
        }
    }

    @Test
    fun `cancelled floating separation should retain its costs without publishing a point`() {
        var cancelled = false
        val funding = SourceLpFunding(1024L, 4096L).also { it.grant(1024L) }
        val lp = LinearAuthorityLp(SourceLpBudget(funding = funding, onWork = {
            if (it.preparationWork > 1L) cancelled = true
        }))
        val result = FloatLpResult(Basis(intArrayOf(), emptyArray()), 0.0, doubleArrayOf(), doubleArrayOf(0.5, 0.75))

        val point = lp.separationPoint(result, 2, Cancellation { cancelled })

        assertNull(point)
        assertTrue(funding.spent > 0L)
        assertTrue(funding.allocated > 0L)
        lp.close()
    }

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
