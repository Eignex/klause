package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PolynomialSectionSelectionTest {
    private val arithmetic = PolynomialArithmetic()
    private val x = Polynomial.variable(0)
    private val y = Polynomial.variable(1)
    private val z = Polynomial.variable(2)

    @Test
    fun `family multiplicities update the preferred total section`() {
        val first = arithmetic.subtract(x, arithmetic.power(y, 2))
        val second = arithmetic.subtract(z, arithmetic.power(y, 2))
        val selection = PolynomialSectionSelection(arithmetic)
        selection.select(listOf(first, second), listOf(first, second, z))

        val selected = selection.select(listOf(first, second), listOf(first, second, x, x))

        assertEquals(2, selected?.variable)
    }

    @Test
    fun `removing a family polynomial removes its occurrence penalty`() {
        val first = arithmetic.subtract(x, arithmetic.power(y, 2))
        val second = arithmetic.subtract(z, arithmetic.power(y, 2))
        val selection = PolynomialSectionSelection(arithmetic)
        selection.select(listOf(first, second), listOf(first, second, z))

        val selected = selection.select(listOf(first, second), listOf(first, second))

        assertEquals(2, selected?.variable)
    }

    @Test
    fun `a removed required equation cannot supply the next section`() {
        val first = arithmetic.subtract(x, arithmetic.power(y, 2))
        val second = arithmetic.subtract(z, arithmetic.power(y, 2))
        val family = listOf(first, second, z)
        val selection = PolynomialSectionSelection(arithmetic)
        selection.select(listOf(first, second), family)

        val selected = selection.select(listOf(second), family)

        assertEquals(2, selected?.variable)
    }

    @Test
    fun `equally ranked equations retain their current source order`() {
        val first = arithmetic.subtract(x, arithmetic.power(y, 2))
        val second = arithmetic.subtract(x, arithmetic.power(z, 2))
        for ((before, after, expected) in listOf(
            Triple(listOf(first, second), listOf(second, first), arithmetic.power(z, 2)),
            Triple(listOf(second, first), listOf(first, second), arithmetic.power(y, 2)),
        )) {
            val selection = PolynomialSectionSelection(arithmetic)
            selection.select(before, listOf(first, second))

            val selected = selection.select(after, listOf(first, second))

            assertEquals(expected, selected?.replacement)
        }
    }

    @Test
    fun `changed equation coefficients update the scalar tie breaker`() {
        val first = arithmetic.subtract(x, arithmetic.power(y, 2))
        val second = arithmetic.subtract(arithmetic.scale(z, BigFraction.ofLong(16)), arithmetic.power(y, 2))
        val changed = arithmetic.subtract(z, arithmetic.power(y, 2))
        val selection = PolynomialSectionSelection(arithmetic)
        selection.select(listOf(first, second), listOf(first, second))

        val selected = selection.select(listOf(first, changed), listOf(first, changed))

        assertEquals(2, selected?.variable)
    }

    @Test
    fun `a variable coefficient cannot replace a previously total section`() {
        val first = arithmetic.subtract(x, arithmetic.power(y, 2))
        val second = arithmetic.subtract(arithmetic.multiply(x, y), Polynomial.ONE)
        val selection = PolynomialSectionSelection(arithmetic)
        selection.select(listOf(first), listOf(first))

        val selected = selection.select(listOf(second), listOf(second))

        assertNull(selected)
    }

    @Test
    fun `rational section coefficients retain their exact sign and denominator`() {
        val square = arithmetic.power(y, 2)
        val selection = PolynomialSectionSelection(arithmetic)
        for (coefficient in listOf(BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.ofLong(2).reciprocal())) {
            val equation = arithmetic.subtract(arithmetic.scale(x, coefficient), square)

            val selected = selection.select(listOf(equation), listOf(equation))

            assertEquals(arithmetic.scale(square, coefficient.reciprocal()), selected?.replacement)
        }
    }

    @Test
    fun `a cached section observes newly requested cancellation`() {
        val equation = arithmetic.subtract(x, arithmetic.power(y, 2))
        var cancelled = false
        val selection = PolynomialSectionSelection(PolynomialArithmetic(Cancellation { cancelled }))
        selection.select(listOf(equation), listOf(equation))
        cancelled = true

        assertFailsWith<PolynomialInterrupted> {
            selection.select(listOf(equation), listOf(equation))
        }
    }

    @Test
    fun `a stopped family update can be retried without stale occurrence counts`() {
        val first = arithmetic.subtract(x, arithmetic.power(y, 2))
        val second = arithmetic.subtract(z, arithmetic.power(y, 2))
        var polls = Int.MAX_VALUE
        val selection = PolynomialSectionSelection(PolynomialArithmetic(Cancellation { --polls <= 0 }))
        selection.select(listOf(first, second), listOf(first, second, z))
        polls = 16
        assertFailsWith<PolynomialInterrupted> {
            selection.select(listOf(first, second), listOf(first, second, x, x))
        }
        polls = Int.MAX_VALUE

        val selected = selection.select(listOf(first, second), listOf(first, second, x, x))

        assertEquals(2, selected?.variable)
    }

    @Test
    fun `a cached section consumes its remaining work allowance`() {
        val equation = arithmetic.subtract(x, arithmetic.power(y, 2))
        var work = 0L
        var limit = Long.MAX_VALUE
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = work >= limit
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val selection = PolynomialSectionSelection(PolynomialArithmetic(token))
        selection.select(listOf(equation), listOf(equation))
        limit = work + 1

        assertFailsWith<PolynomialInterrupted> {
            selection.select(listOf(equation), listOf(equation))
        }
    }
}
