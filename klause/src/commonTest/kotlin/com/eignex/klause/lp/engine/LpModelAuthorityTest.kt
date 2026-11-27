package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LpModelAuthorityTest {
    @Test
    fun `compact logical integrality follows nonzero continuous support`() {
        val base = LpBuilder().apply {
            val x = addVar(-2L, 4L)
            val y = addVar(1L, 4L)
            addRow(intArrayOf(x, y), longArrayOf(3L, -2L), Relation.LE, 6L)
            addRow(intArrayOf(y), longArrayOf(9L), Relation.LE, 30L)
        }.build(Sense.MINIMIZE)
        val compact = LpModel(
            base.n, base.m, base.csc, base.rhs, base.cost, base.upper, base.hasUpper,
            base.loShift, base.objConstant, base.sense, base.tag, colContinuous = booleanArrayOf(true, false),
        )

        val exact = assertNotNull(compact.authoritativeModel())

        assertEquals(listOf(false, true), (compact.n until compact.numVars).map { exact.column(it).integral })
    }

    @Test
    fun `compact coefficients retain signed values across the small scalar range`() {
        val coefficients = longArrayOf(-9L, -8L, 8L, 9L, 8L)
        val model = LpBuilder().apply {
            val x = addVar(0L, 10L)
            for (coefficient in coefficients) addRow(intArrayOf(x), longArrayOf(coefficient), Relation.LE, 10L)
        }.build(Sense.MINIMIZE)

        val exact = assertNotNull(model.authoritativeModel())

        assertEquals(coefficients.toList(), exact.entries(0).map { it.number.exactLong() })
    }

    @Test
    fun `IEEE bounds origins and objective should retain their source bits`() {
        val model = LpBuilder().apply { addRealVar(-0.0, 0.1, cost = 0.3, tag = 7) }.build(Sense.MAXIMIZE)

        val exact = assertNotNull(model.authoritativeModel())

        assertEquals(model.loShiftD(0).toRawBits(), exact.column(0).origin.ieeeBits)
        assertEquals(model.upperD(0).toRawBits(), exact.column(0).bounds.upper?.number?.ieeeBits)
        assertEquals(model.costD(0).toRawBits(), exact.objective.cost(0).ieeeBits)
        assertEquals(model.objConstantD.toRawBits(), exact.objective.constant.ieeeBits)
        assertEquals(Sense.MAXIMIZE, exact.objective.sense)
        assertEquals(7, exact.column(0).tag)
        assertFalse(exact.column(0).integral)
    }

    @Test
    fun `a continuous row should not make its logical column integral`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 2.0)
            addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.LE, 1.0)
        }.build(Sense.MINIMIZE)

        val exact = assertNotNull(model.authoritativeModel())

        assertFalse(exact.column(model.n).integral)
    }

    @Test
    fun `cancellation should not publish a strict witness`() {
        val model = LpBuilder().apply {
            val x = addRealVar(0.0, 1.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)

        val result = solveAndCertify(model, cancellation = ExecutionControl { true })

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(result.witness)
    }
}
