package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PolynomialRemainderTest {
    private val arithmetic = PolynomialArithmetic()
    private val x = Polynomial.variable(0)
    private val y = Polynomial.variable(1)

    @Test
    fun `ordered remainders retain exact rational divisor coefficients`() {
        val xSquared = arithmetic.power(x, 2)
        val ySquared = arithmetic.power(y, 2)
        val divisors = listOf(Polynomial.ZERO,
            arithmetic.subtract(arithmetic.scale(xSquared, BigFraction.ofLong(2)), Polynomial.ONE),
            arithmetic.subtract(arithmetic.scale(ySquared, BigFraction.ofLong(3)),
                Polynomial.constant(BigFraction.ofLong(2))))
        val product = arithmetic.multiply(x, y)
        val polynomial = arithmetic.add(arithmetic.add(xSquared, ySquared), product)

        val remainder = preparePolynomialRemainder(polynomial, divisors, arithmetic)
            .asSequence().filterNotNull().single()

        val constant = BigFraction.ofLong(7) * BigFraction.ofLong(6).reciprocal()
        assertEquals(arithmetic.add(product, Polynomial.constant(constant)), remainder)
    }

    @Test
    fun `an irreducible leading term retains reductions of lower terms`() {
        val xSquared = arithmetic.power(x, 2)
        val polynomial = arithmetic.add(y, xSquared)
        val divisor = arithmetic.subtract(xSquared, Polynomial.constant(BigFraction.ofLong(2)))

        val remainder = preparePolynomialRemainder(polynomial, listOf(divisor), arithmetic)
            .asSequence().filterNotNull().single()

        assertEquals(arithmetic.add(y, Polynomial.constant(BigFraction.ofLong(2))), remainder)
    }

    @Test
    fun `cancelled remainder preparation withholds its unfinished value`() {
        val polynomial = arithmetic.add(y, arithmetic.power(x, 2))
        val divisor = arithmetic.subtract(arithmetic.power(x, 2), Polynomial.ONE)
        var stopped = false
        val pending = preparePolynomialRemainder(polynomial, listOf(divisor),
            PolynomialArithmetic(Cancellation { stopped }))
        assertNull(pending.next())

        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.asSequence().filterNotNull().single() }
    }

    @Test
    fun `unrepresentable remainder powers decline without a partial polynomial`() {
        val large = Polynomial.of(listOf(PolynomialTerm(Monomial.of(mapOf(0 to Int.MAX_VALUE)),
            BigFraction.ONE)))
        val divisor = arithmetic.subtract(y, large)
        val polynomial = arithmetic.power(y, 2)
        val pending = preparePolynomialRemainder(polynomial, listOf(divisor), arithmetic)

        val failure = assertFailsWith<PolynomialResourceLimit> {
            pending.asSequence().filterNotNull().single()
        }

        assertEquals(PolynomialResource.DEGREE_OVERFLOW, failure.resource)
    }

    @Test
    fun `remainder division retains supplied priority across leading coordinates`() {
        val product = arithmetic.multiply(x, y)
        val square = arithmetic.power(x, 2)
        val first = arithmetic.subtract(product, Polynomial.ONE)
        val second = arithmetic.subtract(square, Polynomial.ONE)
        val polynomial = arithmetic.multiply(square, y)

        for ((divisors, expected) in listOf(listOf(first, second) to x, listOf(second, first) to y)) {
            val remainder = PolynomialRemainder(divisors, arithmetic).prepare(polynomial)
                .asSequence().filterNotNull().single()

            assertEquals(expected, remainder)
        }
    }

    @Test
    fun `a shared leading coordinate cannot replace missing divisor coordinates`() {
        val product = arithmetic.subtract(arithmetic.multiply(x, y), Polynomial.ONE)
        val square = arithmetic.power(y, 2)
        val divisor = arithmetic.subtract(square, Polynomial.constant(BigFraction.ofLong(3)))

        val remainder = PolynomialRemainder(listOf(product, divisor), arithmetic).prepare(square)
            .asSequence().filterNotNull().single()

        assertEquals(Polynomial.constant(BigFraction.ofLong(3)), remainder)
    }

    @Test
    fun `constant divisors reduce dividends without a shared variable`() {
        val divisors = listOf(Polynomial.ZERO, Polynomial.constant(BigFraction.ofLong(2)))
        val polynomial = arithmetic.add(arithmetic.multiply(x, y), Polynomial.ONE)

        val remainder = PolynomialRemainder(divisors, arithmetic).prepare(polynomial)
            .asSequence().filterNotNull().single()

        assertEquals(Polynomial.ZERO, remainder)
    }

    @Test
    fun `remainder division captures the supplied divisor list`() {
        val square = arithmetic.power(x, 2)
        val divisors = mutableListOf(arithmetic.subtract(square, Polynomial.ONE))
        val remainders = PolynomialRemainder(divisors, arithmetic)

        divisors.clear()
        val remainder = remainders.prepare(square).asSequence().filterNotNull().single()

        assertEquals(Polynomial.ONE, remainder)
    }

    @Test
    fun `shared divisors retain independent suspended dividend reductions`() {
        val squareX = arithmetic.power(x, 2)
        val squareY = arithmetic.power(y, 2)
        val remainders = PolynomialRemainder(listOf(arithmetic.subtract(squareX, Polynomial.ONE),
            arithmetic.subtract(squareY, Polynomial.constant(BigFraction.ofLong(2)))), arithmetic)
        val first = remainders.prepare(arithmetic.add(squareX, y))
        val second = remainders.prepare(arithmetic.add(x, squareY))
        assertNull(first.next())
        assertNull(second.next())

        val secondResult = second.asSequence().filterNotNull().single()
        val firstResult = first.asSequence().filterNotNull().single()

        assertEquals(arithmetic.add(y, Polynomial.ONE), firstResult)
        assertEquals(arithmetic.add(x, Polynomial.constant(BigFraction.ofLong(2))), secondResult)
    }

    @Test
    fun `shared divisors honor cancellation between dividend reductions`() {
        val square = arithmetic.power(x, 2)
        val divisor = arithmetic.subtract(square, Polynomial.ONE)
        var stopped = false
        val remainders = PolynomialRemainder(listOf(divisor), PolynomialArithmetic(Cancellation { stopped }))
        assertEquals(Polynomial.ONE, remainders.prepare(square).asSequence().filterNotNull().single())

        stopped = true

        assertFailsWith<PolynomialInterrupted> {
            remainders.prepare(square).asSequence().filterNotNull().single()
        }
    }
}
