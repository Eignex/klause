package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ReconstructionAuthorityTest {
    @Test
    fun `repeated matrix captures should preserve every admission charge`() {
        val source = assertNotNull(LpBuilder().apply {
            addVar(0L, 5L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.LE, 7L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val sibling = source.copy()
        val firstMeter = ReconstructionMeter()
        val secondMeter = ReconstructionMeter()

        val first = ReconstructionAuthority(assertNotNull(LpExactState(source).toWorkingModel()), firstMeter)
        val second = ReconstructionAuthority(assertNotNull(LpExactState(sibling).toWorkingModel()), secondMeter)

        assertEquals(first.matrix, second.matrix)
        assertEquals(firstMeter.snapshot(null), secondMeter.snapshot(null))
    }

    @Test
    fun `a retained matrix should preserve tighter admission refusals`() {
        val source = assertNotNull(LpBuilder().apply {
            addVar(0L, 5L)
            addRow(intArrayOf(0), longArrayOf(1L shl 40), Relation.LE, 7L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        ReconstructionAuthority(model, ReconstructionMeter())
        val limits = listOf(
            ReconstructionLimits(maxWork = 0) to ReconstructionDecline.WORK,
            ReconstructionLimits(maxAllocation = 0) to ReconstructionDecline.ALLOCATION,
            ReconstructionLimits(maxEntries = 0) to ReconstructionDecline.DIMENSION,
            ReconstructionLimits(maxBits = 24) to ReconstructionDecline.BITS,
        )

        for ((limit, reason) in limits) {
            val stopped = assertFailsWith<ReconstructionStop> {
                ReconstructionAuthority(model, ReconstructionMeter(limit))
            }
            assertEquals(reason, stopped.reason)
        }
        val cancelled = assertFailsWith<ReconstructionStop> {
            ReconstructionAuthority(model, ReconstructionMeter(cancellation = ExecutionControl { true }))
        }
        assertEquals(ReconstructionDecline.CANCELLED, cancelled.reason)
    }

    @Test
    fun `a shared matrix should read current bounds origins and objective`() {
        val source = assertNotNull(LpBuilder().apply {
            addVar(0L, 5L, cost = 2L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.LE, 7L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        ReconstructionAuthority(assertNotNull(LpExactState(source).toWorkingModel()), ReconstructionMeter())
        val changed = source.copy(
            columns = listOf(source.column(0).copy(bounds = ExactLpBounds(
                ExactLpSide(ExactLpNumber.of(1L)), ExactLpSide(ExactLpNumber.of(4L)),
            )), source.column(1)),
            objective = ExactLpObjective(listOf(ExactLpNumber.of(5L), ExactLpNumber.of(0L))),
        ).recentered(listOf(ExactLpNumber.of(1L)))

        val current = ReconstructionAuthority(assertNotNull(LpExactState(changed).toWorkingModel()),
            ReconstructionMeter())

        assertEquals(BigFraction.ZERO, current.bounds[0].lower?.number?.value)
        assertEquals(BigFraction.ofLong(3L), current.bounds[0].upper?.number?.value)
        assertEquals(listOf(BigFraction.ofLong(4L)), current.rhs)
        assertEquals(listOf(BigFraction.ONE), current.origins)
        assertEquals(BigFraction.ofLong(5L), current.costs[0])
        assertEquals(BigFraction.ofLong(5L), current.constant)
    }

    @Test
    fun `an interrupted capture should leave later full admission intact`() {
        val source = assertNotNull(LpBuilder().apply {
            addVar(0L, 5L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.LE, 7L)
            addRow(intArrayOf(0), longArrayOf(2L), Relation.LE, 5L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        assertFailsWith<ReconstructionStop> {
            ReconstructionAuthority(model, ReconstructionMeter(ReconstructionLimits(maxEntries = 1)))
        }

        val completed = ReconstructionAuthority(model, ReconstructionMeter())

        assertEquals(listOf(0 to BigFraction.ofLong(3L), 1 to BigFraction.ofLong(2L)), completed.matrix[0])
        assertEquals(listOf(0 to BigFraction.ONE), completed.matrix[1])
        assertEquals(listOf(1 to BigFraction.ONE), completed.matrix[2])
    }

    @Test
    fun `an appended row should read its new matrix after an earlier capture`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 5L) }
            .build(Sense.MINIMIZE).authoritativeModel())
        ReconstructionAuthority(assertNotNull(LpExactState(source).toWorkingModel()), ReconstructionMeter())
        val appended = source.appendScopedRow(LpScopedRow(
            1L, listOf(0 to ExactLpNumber.of(3L)), ExactLpNumber.of(7L),
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))),
        ))

        val current = ReconstructionAuthority(assertNotNull(LpExactState(appended).toWorkingModel()),
            ReconstructionMeter())

        assertEquals(listOf(listOf(0 to BigFraction.ofLong(3L)), listOf(0 to BigFraction.ONE)), current.matrix)
    }
}
