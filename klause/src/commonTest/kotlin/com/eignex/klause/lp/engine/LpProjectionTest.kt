package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpProjectionTest {
    @Test
    fun `matrix underflow preserves structural support and exact authority`() {
        val tiny = BigFraction.of(BigInteger.ONE, BigInteger.TEN.pow(400))
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(tiny)))),
            listOf(zero),
            List(2) { ExactLpColumn(ExactLpBounds()) },
            listOf(ExactLpRow()),
            ExactLpObjective(List(2) { zero }),
        )
        val state = LpExactState(source)

        val working = assertNotNull(state.toWorkingModel())

        assertEquals(1, working.csc.rowIdx.size)
        assertEquals(tiny, state.model.entries(0).single().number.value)
        assertEquals(LpMatrixProjectionStatus(1, 0), state.matrixProjectionStatus)
    }

    @Test
    fun `budget and cancellation declines permit a later projection`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val state = LpExactState(source)

        assertNull(state.toWorkingModel(LpProjectionMeter(workLimit = 0L)))
        assertNull(state.toWorkingModel(LpProjectionMeter(cancellation = Cancellation { true })))

        assertFalse(state.matrixProjectionDeclined)
        assertNotNull(state.toWorkingModel())
    }

    @Test
    fun `completed matrix survives vector budget decline and is reused`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val state = LpExactState(source)
        val setupWork = source.n.toLong() + source.m + 1L + source.keySize

        assertNull(state.toWorkingModel(LpProjectionMeter(workLimit = setupWork)))
        val retry = LpProjectionMeter()
        assertNotNull(state.toWorkingModel(retry))

        assertNotNull(state.matrixProjectionStatus)
        assertEquals(0L, retry.matrixWork)
        assertTrue(retry.vectorWork > 0L)
    }

    @Test
    fun `right hand side copies do not alias mutable input`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(zero),
            List(2) { ExactLpColumn(ExactLpBounds()) },
            listOf(ExactLpRow()),
            ExactLpObjective(List(2) { zero }),
        )
        val rhs = mutableListOf(ExactLpNumber.of(2L))

        val copied = source.copy(rhs = rhs)
        rhs[0] = zero

        assertEquals(BigFraction.ofLong(2L), copied.rhs(0).value)
        assertEquals(BigFraction.ZERO, source.rhs(0).value)
        assertTrue(source.sameMatrix(copied))
    }
}
