package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashBasisTest {
    @Test
    fun `triangular columns replace logicals in source row order`() {
        val builder = LpBuilder()
        val first = builder.addVar(0L, 10L)
        val second = builder.addVar(0L, 10L)
        val third = builder.addVar(0L, 10L)
        builder.addRow(intArrayOf(first, second, third), longArrayOf(1L, 1L, 1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(second, third), longArrayOf(1L, 1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(third), longArrayOf(1L), Relation.GE, 1L)

        val attempt = triangularCrashBasis(builder.build(Sense.MINIMIZE))

        assertContentEquals(intArrayOf(first, second, third), assertNotNull(attempt.basis).basicVars)
        assertEquals(3, attempt.metrics.selected)
        assertNull(attempt.metrics.decline)
        assertTrue(attempt.metrics.workOps > 0L)
    }

    @Test
    fun `nonbasic seats follow exact reduced cost signs`() {
        val builder = LpBuilder()
        val basic = builder.addVar(0L, 10L)
        val lower = builder.addVar(0L, 10L, cost = 2L)
        val upper = builder.addVar(0L, 10L, cost = -3L)
        builder.addRow(intArrayOf(basic, lower, upper), longArrayOf(1L, 1L, 1L), Relation.GE, 1L)

        val basis = assertNotNull(triangularCrashBasis(builder.build(Sense.MINIMIZE)).basis)

        assertEquals(VarStatus.BASIC, basis.status[basic])
        assertEquals(VarStatus.AT_LOWER, basis.status[lower])
        assertEquals(VarStatus.AT_UPPER, basis.status[upper])
    }

    @Test
    fun `free and upper only columns retain native nonbasic seats`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one)), emptyList(), emptyList()),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))),
                ExactLpColumn(ExactLpBounds(), integral = false),
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(one)), integral = false),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero, ExactLpNumber.of(-1L), zero)),
        )

        val basis = assertNotNull(triangularCrashBasis(assertNotNull(LpExactState(model).toWorkingModel())).basis)

        assertEquals(VarStatus.FREE, basis.status[1])
        assertEquals(VarStatus.AT_UPPER, basis.status[2])
    }

    @Test
    fun `a missing reduced cost side declines the whole proposal`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one)), emptyList()),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))),
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(one)), integral = false),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, one, zero)),
        )

        val attempt = triangularCrashBasis(assertNotNull(LpExactState(model).toWorkingModel()))

        assertNull(attempt.basis)
        assertEquals(CrashBasisDecline.INVALID_NONBASIC_SEAT, attempt.metrics.decline)
    }

    @Test
    fun `nonzero retained logical cost declines the proposal`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one, one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
            ),
            listOf(ExactLpRow(), ExactLpRow()),
            ExactLpObjective(listOf(zero, zero, one)),
        )

        val attempt = triangularCrashBasis(assertNotNull(LpExactState(model).toWorkingModel()))

        assertNull(attempt.basis)
        assertEquals(CrashBasisDecline.NONZERO_BASIC_COST, attempt.metrics.decline)
    }

    @Test
    fun `cancellation and work ceilings decline without a partial basis`() {
        val builder = LpBuilder()
        val column = builder.addVar(0L, 10L)
        builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        val model = builder.build(Sense.MINIMIZE)

        val cancelled = triangularCrashBasis(model, Cancellation { true })
        val exhausted = triangularCrashBasis(model, workLimit = 1L)

        assertNull(cancelled.basis)
        assertEquals(CrashBasisDecline.CANCELLED, cancelled.metrics.decline)
        assertNull(exhausted.basis)
        assertEquals(CrashBasisDecline.RESOURCE_LIMIT, exhausted.metrics.decline)
    }
}
