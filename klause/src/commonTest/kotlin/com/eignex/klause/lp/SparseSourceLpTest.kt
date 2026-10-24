package com.eignex.klause.lp

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SparseSourceLpTest {
    @Test
    fun `sparse preparation should preserve exact rows columns objective and adjacency`() {
        val third = BigFraction.ofLong(3L).reciprocal()
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), listOf(third, BigFraction.ZERO), BigFraction.ONE, true),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
        )
        val columns = listOf(
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(-2L)))),
            ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(7L)))),
        )

        val preparation = SparseSourceLp.prepare(rows, columns, listOf(third, BigFraction.ONE), third, limits)
        val prepared = assertNotNull(preparation.prepared)
        val model = prepared.model

        assertNull(preparation.decline)
        assertEquals(3L, preparation.metrics.inputEntries)
        assertEquals(2L, preparation.metrics.emittedEntries)
        assertEquals(third, model.entries(0).single().number.value)
        assertEquals(1, model.entries(1).single().row)
        assertEquals(columns[0], model.column(0))
        assertEquals(columns[1], model.column(1))
        assertTrue(model.row(0).strict)
        assertTrue(assertNotNull(model.column(2).bounds.lower).strict)
        assertEquals(BigFraction.ONE, model.rhs(0).value)
        assertEquals(third, model.objective.cost(0).value)
        assertEquals(third, model.objective.constant.value)
        assertContentEquals(intArrayOf(0), prepared.rowColumns(0))
        assertContentEquals(intArrayOf(1), prepared.columnRows(1))
        prepared.rowColumns(0)[0] = 1
        assertContentEquals(intArrayOf(0), prepared.rowColumns(0))
    }

    @Test
    fun `sparse preparation should admit support beyond the exact primal dimension guard`() {
        val rows = List(129) {
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(it.toLong()))
        }

        val preparation = prepare(rows, limits)

        assertFalse(SourceLpBudget().admitsDimensions(1, rows.size.toLong()))
        assertEquals(129, assertNotNull(preparation.prepared).model.m)
        assertTrue(preparation.metrics.work < limits.work)
        assertTrue(preparation.metrics.allocation < limits.allocation)
    }

    @Test
    fun `sparse preparation should distinguish work allocation width shape and cancellation declines`() {
        val row = ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE)
        val cases = listOf(
            Triple(listOf(row), limits.copy(work = 0L), SparseSourceLpDecline.WORK),
            Triple(listOf(row), limits.copy(allocation = 0L), SparseSourceLpDecline.ALLOCATION),
            Triple(listOf(row), limits.copy(scalarBits = 1), SparseSourceLpDecline.SCALAR_WIDTH),
            Triple(listOf(ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ONE)),
                limits, SparseSourceLpDecline.SHAPE),
        )
        for ((rows, allowance, reason) in cases) {
            val result = prepare(rows, allowance, cost = BigFraction.ofLong(2L))

            assertNull(result.prepared)
            assertEquals(reason, result.decline)
        }
        val cancelled = prepare(listOf(row), limits, cancellation = Cancellation { true })
        assertNull(cancelled.prepared)
        assertEquals(SparseSourceLpDecline.CANCELLED, cancelled.decline)
    }

    @Test
    fun `sparse budget should retain declined preparation costs without entering the solver`() {
        val budget = SourceLpBudget()
        var entered = false
        val rows = List(8) { ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE) }

        val result = budget.runSparse(Cancellation.Never, prepare = {
            prepare(rows, limits.copy(work = 100L), cancellation = it)
        }) { _, _ ->
            entered = true
            true
        }

        assertNull(result)
        assertFalse(entered)
        assertEquals(1, budget.operations)
        assertTrue(budget.reservedWork > 1024L)
        assertTrue(budget.reservedAllocation > 0L)
        assertTrue(budget.measuredPreparationWork > 0L)
    }

    @Test
    fun `exhausted sparse budget should skip preparation`() {
        var prepared = false

        val result = SourceLpBudget(maxOperations = 0).runSparse(Cancellation.Never, prepare = {
            prepared = true
            prepare(emptyList(), limits)
        }) { _, _ -> true }

        assertNull(result)
        assertFalse(prepared)
    }

    @Test
    fun `sparse admission should resume without reserving or consuming another operation`() {
        val budget = SourceLpBudget(maxOperations = 1)
        val admission = assertNotNull(budget.admitSparse(Cancellation.Never) { prepare(emptyList(), limits) })
        val reservedWork = budget.reservedWork
        val reservedAllocation = budget.reservedAllocation

        repeat(3) {
            val resumed = budget.resumeSparse(admission, Cancellation.Never) { prepared, _ ->
                budget.observe(floatWork = 7L, boundCertification = 11L)
                prepared
            }
            assertSame(admission.prepared, resumed)
        }

        assertEquals(1, budget.operations)
        assertEquals(reservedWork, budget.reservedWork)
        assertEquals(reservedAllocation, budget.reservedAllocation)
        assertEquals(21L, budget.measuredFloatWork)
        assertEquals(33L, budget.measuredBoundCertificationWork)
        admission.close()
        assertNull(budget.resumeSparse(admission, Cancellation.Never) { _, _ -> true })
    }

    @Test
    fun `changed sparse consultations should spend operations without repeating matrix reservations`() {
        val budget = SourceLpBudget(maxOperations = 2)
        val initial = assertNotNull(budget.admitSparse(Cancellation.Never) { prepare(emptyList(), limits) })
        val reservedWork = budget.reservedWork
        val reservedAllocation = budget.reservedAllocation
        val preparationWork = budget.measuredPreparationWork

        val next = assertNotNull(budget.admitSparse(initial.prepared, Cancellation.Never))

        assertSame(initial.prepared, next.prepared)
        assertEquals(2, budget.operations)
        assertEquals(reservedWork + 1024L, budget.reservedWork)
        assertEquals(reservedAllocation, budget.reservedAllocation)
        assertEquals(preparationWork, budget.measuredPreparationWork)
        assertNull(budget.admitSparse(next.prepared, Cancellation.Never))
    }

    private fun prepare(
        rows: List<ExactRationalInequality>,
        allowance: SparseSourceLpLimits,
        cost: BigFraction = BigFraction.ONE,
        cancellation: Cancellation = Cancellation.Never,
    ) = SparseSourceLp.prepare(rows, listOf(ExactLpColumn(ExactLpBounds())), listOf(cost),
        BigFraction.ZERO, allowance, cancellation)

    private val limits = SparseSourceLpLimits(100_000L, 4L * 1024L * 1024L)
}
