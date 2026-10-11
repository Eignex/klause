package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PolynomialArithmeticTest {
    private val arithmetic = PolynomialArithmetic()
    private val x = Polynomial.variable(0)
    private val y = Polynomial.variable(1)

    @Test
    fun `terms merge and cancel independent of producer mutation`() {
        val powers = mutableMapOf(0 to 2)
        val monomial = Monomial.of(powers)
        val terms = mutableListOf(PolynomialTerm(monomial, BigFraction.ONE))
        val polynomial = Polynomial.of(terms)

        powers[0] = 7
        terms.clear()

        assertEquals(2, polynomial.degree(0))
        assertEquals(Polynomial.ZERO, arithmetic.subtract(polynomial, polynomial))
    }

    @Test
    fun `exact division reconstructs a multivariate product`() {
        val divisor = arithmetic.add(x, y)
        val quotient = arithmetic.subtract(arithmetic.power(x, 2), y)
        val product = arithmetic.multiply(divisor, quotient)

        assertEquals(quotient, arithmetic.divideExact(product, divisor))
    }

    @Test
    fun `resultant eliminates the selected variable with exact coefficients`() {
        val left = arithmetic.subtract(arithmetic.power(y, 2), x)
        val right = arithmetic.subtract(y, Polynomial.constant(BigFraction.ofLong(3)))

        val result = arithmetic.resultant(left, right, 1)

        assertEquals(arithmetic.subtract(Polynomial.constant(BigFraction.ofLong(9)), x), result)
    }

    @Test
    fun `principal subresultants detect a shared factor beyond the resultant`() {
        val left = arithmetic.multiply(
            arithmetic.multiply(arithmetic.subtract(x, constant(1)), arithmetic.subtract(x, constant(2))),
            arithmetic.subtract(x, constant(3)),
        )
        val right = arithmetic.multiply(arithmetic.subtract(x, constant(2)), arithmetic.subtract(arithmetic.scale(x, fraction(2)), constant(1)))

        val result = arithmetic.principalSubresultants(left, right, 0)

        assertEquals(listOf(Polynomial.ZERO, constant(5)), result)
    }

    @Test
    fun `constant and zero resultants retain elimination semantics`() {
        for ((left, right, expected) in listOf(
            Triple(Polynomial.ZERO, x, Polynomial.ZERO),
            Triple(constant(3), arithmetic.power(x, 2), constant(9)),
            Triple(arithmetic.power(x, 2), constant(3), constant(9)),
            Triple(constant(2), constant(3), Polynomial.ONE),
        )) {
            assertEquals(expected, arithmetic.resultant(left, right, 0))
        }
    }

    @Test
    fun `substitution detects a vanished leading coefficient`() {
        val polynomial = arithmetic.add(arithmetic.multiply(x, arithmetic.power(y, 2)), y)

        val specialized = arithmetic.substitute(polynomial, 0, Polynomial.ZERO)

        assertEquals(y, specialized)
    }

    @Test
    fun `cancelled projection publishes no partial determinant`() {
        var work = 0
        val cancelled = PolynomialArithmetic(Cancellation { ++work > 5 })

        assertFailsWith<PolynomialInterrupted> { cancelled.resultant(arithmetic.power(x, 3), arithmetic.add(x, y), 0) }
        assertTrue(work > 5)
    }

    private fun fraction(value: Long): BigFraction = BigFraction.ofLong(value)
    private fun constant(value: Long): Polynomial = Polynomial.constant(fraction(value))
}
