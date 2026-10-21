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
}
