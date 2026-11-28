package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LpExactOptimizationTest {
    @Test
    fun `exact certification should admit a finite endpoint beyond floating range`() {
        val value = BigFraction.of(BIG_ONE shl 1100, BIG_ONE)
        val source = model(listOf(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(value)))), listOf(one))

        val result = immediateLpSolve { optimize(source) }

        assertEquals(listOf(value), assertIs<LpExactOptimization.Optimum>(result).point)
    }

    @Test
    fun `strict coordinates should attain an objective that is constant along their optimal face`() {
        val source = model(listOf(ExactLpBounds(lower = zero), ExactLpBounds(lower = strictZero)), listOf(one, nil))

        val result = immediateLpSolve { optimize(source) }

        val point = assertIs<LpExactOptimization.Optimum>(result).point
        assertEquals(BigFraction.ZERO, point[0])
        assertTrue(point[1] > BigFraction.ZERO)
    }

    @Test
    fun `a strict side should leave its infimum unattained across a pause`() {
        val source = model(listOf(ExactLpBounds(lower = strictZero)), listOf(one))
        var work = 0L

        val result = LpSolveCursor<LpExactOptimization> {
            optimize(source, observe = { work += it }) { checkpoint(work) }
        }.use { cursor ->
            assertEquals(LpSolveStep.Paused, cursor.advance(1))
            val pausedWork = work
            val finished = assertIs<LpSolveStep.Completed<LpExactOptimization>>(cursor.advance()).value
            assertTrue(work > pausedWork)
            finished
        }

        assertEquals(listOf(BigFraction.ZERO), assertIs<LpExactOptimization.Infimum>(result).endpoint)
    }

    @Test
    fun `an exact pivot batch should resume to an optimum beyond its first allowance`() {
        val columns = 11
        val box = ExactLpBounds(zero, ExactLpSide(one))
        val source = model(List(columns) { box }, List(columns) { ExactLpNumber.of(-1L) })
        var work = 0L

        val result = LpSolveCursor<LpExactOptimization> {
            optimize(source, observe = { work += it }) { checkpoint(work) }
        }.use { cursor ->
            assertEquals(LpSolveStep.Paused, cursor.advance(1))
            assertEquals(LpSolveStep.Paused, cursor.advance(1))
            assertIs<LpSolveStep.Completed<LpExactOptimization>>(cursor.advance()).value
        }

        assertEquals(List(columns) { BigFraction.ONE }, assertIs<LpExactOptimization.Optimum>(result).point)
    }

    @Test
    fun `the closure phase should spend the remainder of exact work`() {
        val source = model(listOf(ExactLpBounds(lower = strictZero)), listOf(one))
        var total = 0L
        assertIs<LpExactOptimization.Infimum>(immediateLpSolve { optimize(source, observe = { total += it }) })
        val limit = total - 1
        var spent = 0L

        val limits = ExactContinuationLimits(maxWork = limit)
        val result = immediateLpSolve { optimize(source, limits, observe = { spent += it }) }

        assertEquals(ContinuationDecline.WORK, assertIs<LpExactOptimization.Declined>(result).reason)
        assertTrue(spent in 1..limit)
    }

    @Test
    fun `an explicit resource limit should terminate exact optimization`() {
        val source = model(listOf(ExactLpBounds(lower = zero)), listOf(one))
        val stops = listOf(
            ExactContinuationLimits(maxPivots = 0) to ContinuationDecline.PIVOTS,
            ExactContinuationLimits(maxTimeNs = 0) to ContinuationDecline.TIME,
        )

        for ((limits, stop) in stops) {
            val result = immediateLpSolve { optimize(source, limits) }

            assertEquals(stop, assertIs<LpExactOptimization.Declined>(result).reason)
        }
    }

    @Test
    fun `a checked improving direction should survive an infeasible primal dual query`() {
        val source = model(listOf(ExactLpBounds()), listOf(one))

        val result = immediateLpSolve { optimize(source) }

        assertEquals(listOf(BigFraction.MINUS_ONE), assertIs<LpExactOptimization.Unbounded>(result).direction)
    }

    @Test
    fun `cancellation at a proof checkpoint should withhold the proof`() {
        val source = model(listOf(ExactLpBounds(lower = zero)), listOf(one))
        var cancelled = false
        var work = 0L

        val result = immediateLpSolve {
            optimize(source, cancellation = ExecutionControl { cancelled }, observe = { work += it }) {
                cancelled = true
            }
        }

        assertEquals(ContinuationDecline.CANCELLED, assertIs<LpExactOptimization.Declined>(result).reason)
        assertTrue(work > 0)
    }

    @Test
    fun `cancellation at the last exact debit should withhold the proof`() {
        val source = model(listOf(ExactLpBounds(lower = zero)), listOf(one))
        var total = 0L
        assertIs<LpExactOptimization.Optimum>(immediateLpSolve { optimize(source, observe = { total += it }) })
        var charged = 0L

        val result = immediateLpSolve {
            optimize(source, cancellation = ExecutionControl { charged >= total }, observe = { charged += it })
        }

        assertEquals(ContinuationDecline.CANCELLED, assertIs<LpExactOptimization.Declined>(result).reason)
        assertEquals(total, charged)
    }

    @Test
    fun `an unfunded tableau should decline before allocating an auxiliary`() {
        val source = model(listOf(ExactLpBounds(lower = zero)), listOf(one))
        var allocated = 0L

        val result = immediateLpSolve {
            optimizeFeasibleExactLp(source, ExactContinuationLimits(maxAllocation = 1), ExecutionControl.Never,
                ProductionLpCertificationPolicy, { _, bytes, _, _ -> allocated += bytes }) {}
        }

        assertEquals(ContinuationDecline.ALLOCATION, assertIs<LpExactOptimization.Declined>(result).reason)
        assertEquals(0L, allocated)
    }

    private val nil = ExactLpNumber.of(0L)
    private val one = ExactLpNumber.of(1L)
    private val zero = ExactLpSide(nil)
    private val strictZero = ExactLpSide(nil, strict = true)

    private fun model(bounds: List<ExactLpBounds>, costs: List<ExactLpNumber>) =
        ExactLpModel(List(bounds.size) { emptyList() }, emptyList(),
            bounds.map { ExactLpColumn(it, integral = false) }, emptyList(), ExactLpObjective(costs))

    private suspend fun optimize(
        source: ExactLpModel,
        limits: ExactContinuationLimits = ExactContinuationLimits(),
        cancellation: ExecutionControl = ExecutionControl.Never,
        observe: (Long) -> Unit = {},
        checkpoint: suspend () -> Unit = {},
    ): LpExactOptimization = optimizeFeasibleExactLp(
        source, limits, cancellation, ProductionLpCertificationPolicy, { work, _, _, _ -> observe(work) },
        checkpoint = checkpoint,
    )
}
