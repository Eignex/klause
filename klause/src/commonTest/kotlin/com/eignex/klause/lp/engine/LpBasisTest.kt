package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpBasisTest {
    @Test
    fun `headings are ordered unique and owned`() {
        val headings = mutableListOf(2, 1)
        val statuses = mutableListOf(ExactLpStatus.AT_LOWER, ExactLpStatus.BASIC, ExactLpStatus.BASIC)
        val basis = ExactLpBasis(headings, statuses)

        headings.reverse()
        statuses[1] = ExactLpStatus.FREE

        assertEquals(2, basis.heading(0))
        assertEquals(1, basis.heading(1))
        assertEquals(ExactLpStatus.BASIC, basis.status(1))
        assertFailsWith<IllegalArgumentException> {
            ExactLpBasis(
                listOf(1, 1),
                listOf(ExactLpStatus.AT_LOWER, ExactLpStatus.BASIC),
            )
        }
        assertFailsWith<IllegalArgumentException> { ExactLpBasis(listOf(2), listOf(ExactLpStatus.BASIC)) }
        assertFailsWith<IllegalArgumentException> { ExactLpBasis(emptyList(), listOf(ExactLpStatus.BASIC)) }
    }

    @Test
    fun `nonbasic seats require an existing nonstrict side`() {
        val zero = ExactLpSide(ExactLpNumber.of(0L))
        val one = ExactLpSide(ExactLpNumber.of(1L))
        val cases = listOf(
            Triple(ExactLpBounds(lower = zero), ExactLpStatus.AT_LOWER, true),
            Triple(ExactLpBounds(upper = one), ExactLpStatus.AT_UPPER, true),
            Triple(ExactLpBounds(upper = one), ExactLpStatus.AT_LOWER, false),
            Triple(ExactLpBounds(lower = zero), ExactLpStatus.AT_UPPER, false),
            Triple(ExactLpBounds(), ExactLpStatus.FREE, true),
            Triple(ExactLpBounds(lower = zero), ExactLpStatus.FREE, false),
            Triple(ExactLpBounds(zero, zero), ExactLpStatus.FIXED, true),
            Triple(ExactLpBounds(zero, one), ExactLpStatus.FIXED, false),
            Triple(ExactLpBounds(zero.copy(strict = true), one), ExactLpStatus.AT_LOWER, false),
            Triple(ExactLpBounds(zero, one.copy(strict = true)), ExactLpStatus.AT_UPPER, false),
        )
        for ((bounds, status, valid) in cases) {
            val model = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(bounds)),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(0L))),
            )
            val basis = ExactLpBasis(emptyList(), listOf(status))

            assertEquals(valid, basis.validFor(model), "$bounds at $status")
            assertEquals(status, basis.status(0))
            assertTrue(model.sameAuthority(model.copy()))
        }
    }

    @Test
    fun `crossed or strict equal bounds cannot supply any valid basis`() {
        val zero = ExactLpSide(ExactLpNumber.of(0L))
        for (bounds in listOf(
            ExactLpBounds(zero.copy(strict = true), zero),
            ExactLpBounds(zero, zero.copy(strict = true)),
            ExactLpBounds(ExactLpSide(ExactLpNumber.of(1L)), zero),
        )) {
            val model = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(bounds)),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(0L))),
            )

            assertFalse(bounds.consistent)
            assertFalse(bounds.fixed)
            assertFalse(ExactLpBasis(emptyList(), listOf(ExactLpStatus.FIXED)).validFor(model))
            assertNull(model.toLegacy())
        }
    }

    @Test
    fun `fixed conversion retains exact source status and basis order`() {
        val zero = ExactLpSide(ExactLpNumber.of(0L))
        val model = ExactLpModel(
            listOf(emptyList()),
            listOf(ExactLpNumber.of(0L)),
            listOf(ExactLpColumn(ExactLpBounds(zero, zero)), ExactLpColumn(ExactLpBounds(zero))),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(ExactLpNumber.of(0L), ExactLpNumber.of(0L))),
        )
        val basis = ExactLpBasis(listOf(1), listOf(ExactLpStatus.FIXED, ExactLpStatus.BASIC))

        val converted = assertNotNull(basis.toLegacy(model))

        assertContentEquals(intArrayOf(1), converted.basis.basicVars)
        assertEquals(VarStatus.AT_LOWER, converted.basis.status[0])
        assertEquals(ExactLpStatus.FIXED, converted.exactBasis.status(0))
        assertTrue(converted.source.column(0).bounds.fixed)
        assertTrue(converted.model.hasUpper[0])
        assertEquals(0L, converted.model.upper[0])
    }

    @Test
    fun `free and upper only declarations cannot enter legacy solver`() {
        for ((bounds, status) in listOf(
            ExactLpBounds() to ExactLpStatus.FREE,
            ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(1L))) to ExactLpStatus.AT_UPPER,
        )) {
            val model = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(bounds)),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(0L))),
            )
            val basis = ExactLpBasis(emptyList(), listOf(status))

            assertTrue(basis.validFor(model))
            assertNull(basis.toLegacy(model))
            assertEquals(LpVerdict.INDETERMINATE, solveAndCertify(model, basis).verdict)
        }
    }
}
