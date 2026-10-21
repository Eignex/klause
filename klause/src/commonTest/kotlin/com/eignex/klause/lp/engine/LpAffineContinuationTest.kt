package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationPhase
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpAffineContinuationTest {
    @Test
    fun `a private continuation should lift a feasible original point within unchanged row limits`() {
        val source = assertNotNull(
            LpBuilder().apply {
                addFreeVar(null, null)
                addVar(0L, 10L)
                addRow(intArrayOf(0, 1), longArrayOf(1L, -1L), Relation.EQ, 0L)
                addRow(intArrayOf(1), longArrayOf(1L), Relation.GE, 3L)
            }.build(Sense.MINIMIZE).authoritativeModel(),
        )
        val state = LpExactState(source)

        val result = continueExactLp(
            assertNotNull(state.toWorkingModel()),
            null,
            limits = ExactContinuationLimits(maxRows = 1),
            targetMetrics = ExactContinuationMetrics(
                phase = ContinuationPhase.ADMISSION,
                decline = ContinuationDecline.DIMENSION,
            ),
        )

        val point = assertNotNull(result.witness).primal
        assertEquals(point[0], point[1])
        assertTrue(point[1] >= BigFraction.ofLong(3L))
        assertTrue(result.metrics.success)
        assertNull(result.conflict)
    }

    @Test
    fun `a pop should replace a private conflict with an original feasible point`() {
        val source = assertNotNull(
            LpBuilder().apply {
                addFreeVar(null, null)
                addVar(0L, 10L)
                addRow(intArrayOf(0, 1), longArrayOf(1L, -1L), Relation.EQ, 0L)
                addRow(intArrayOf(0), longArrayOf(1L), Relation.LE, 0L)
            }.build(Sense.MINIMIZE).authoritativeModel(),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(1, false, ExactLpSide(ExactLpNumber.of(1L)), 11L))
        val before = trail.state
        val cache = LpExactContinuationCache()
        val limits = ExactContinuationLimits(maxRows = 1)
        val target = ExactContinuationMetrics(
            phase = ContinuationPhase.ADMISSION,
            decline = ContinuationDecline.DIMENSION,
        )
        val conflict = continueExactLp(
            assertNotNull(before.toWorkingModel()),
            null,
            cache,
            limits = limits,
            targetMetrics = target,
        )
        assertTrue(trail.pop())

        val feasible = continueExactLp(
            assertNotNull(trail.state.toWorkingModel()),
            null,
            cache,
            limits = limits,
            targetMetrics = target,
        )

        assertNotNull(conflict.conflict)
        assertTrue(assertNotNull(conflict.support).state === before)
        assertEquals(listOf(BigFraction.ZERO, BigFraction.ZERO), assertNotNull(feasible.witness).primal)
        assertNull(feasible.conflict)
    }

    @Test
    fun `strict surviving sides should require an interior original witness`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one)), listOf(ExactLpEntry(0, ExactLpNumber.of(-1L)))),
            listOf(zero),
            listOf(
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(one, strict = true))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(List(3) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val result = continueExactLp(
            model,
            null,
            limits = ExactContinuationLimits(maxRows = 0),
            targetMetrics = ExactContinuationMetrics(
                phase = ContinuationPhase.ADMISSION,
                decline = ContinuationDecline.DIMENSION,
            ),
        )

        val point = assertNotNull(result.witness).primal
        assertEquals(point[0], point[1])
        assertTrue(point[1] > BigFraction.ZERO && point[1] < BigFraction.ONE)
    }

    @Test
    fun `cancellation should withhold a previously constructed private witness`() {
        val source = assertNotNull(
            LpBuilder().apply {
                addFreeVar(null, null)
                addVar(0L, 10L)
                addRow(intArrayOf(0, 1), longArrayOf(1L, -1L), Relation.EQ, 0L)
            }.build(Sense.MINIMIZE).authoritativeModel(),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val cache = LpExactContinuationCache()
        val limits = ExactContinuationLimits(maxRows = 0)
        val target = ExactContinuationMetrics(
            phase = ContinuationPhase.ADMISSION,
            decline = ContinuationDecline.DIMENSION,
        )
        assertNotNull(continueExactLp(model, null, cache, limits = limits, targetMetrics = target).witness)

        val stopped = continueExactLp(model, null, cache, Cancellation { true }, limits, targetMetrics = target)

        assertNull(stopped.witness)
        assertNull(stopped.conflict)
        assertEquals(ContinuationDecline.CANCELLED, stopped.metrics.decline)
    }

    @Test
    fun `a resumed private continuation should not replenish spent work`() {
        val source = assertNotNull(
            LpBuilder().apply {
                addFreeVar(null, null)
                addVar(0L, 10L)
                addRow(intArrayOf(0, 1), longArrayOf(1L, -1L), Relation.EQ, 0L)
            }.build(Sense.MINIMIZE).authoritativeModel(),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val cache = LpExactContinuationCache()
        val limits = ExactContinuationLimits(maxRows = 0)
        val target = ExactContinuationMetrics(
            phase = ContinuationPhase.ADMISSION,
            decline = ContinuationDecline.DIMENSION,
        )
        assertNotNull(continueExactLp(model, null, cache, limits = limits, targetMetrics = target).witness)
        val spent = cache.usedWork

        val stopped = continueExactLp(model, null, cache, limits = limits.copy(maxWork = spent), targetMetrics = target)

        assertNull(stopped.witness)
        assertNull(stopped.conflict)
        assertEquals(ContinuationDecline.WORK, stopped.metrics.decline)
        assertTrue(cache.usedWork >= spent)
    }
}
