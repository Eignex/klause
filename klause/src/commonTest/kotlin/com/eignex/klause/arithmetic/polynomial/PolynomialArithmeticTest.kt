package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialArithmeticTest {
    private val arithmetic = PolynomialArithmetic()
    private val x = Polynomial.variable(0)
    private val y = Polynomial.variable(1)

    @Test
    fun `monomial order compares powers at the greatest coordinate first`() {
        for ((left, right, expected) in listOf(
            Triple(emptyMap<Int, Int>(), emptyMap(), 0),
            Triple(mapOf(3 to 1), emptyMap(), 1),
            Triple(mapOf(0 to Int.MAX_VALUE), mapOf(Int.MAX_VALUE to 1), -1),
            Triple(mapOf(7 to 2, 1 to 3), mapOf(7 to 2, 2 to 1), -1),
            Triple(mapOf(7 to 3), mapOf(7 to 2, 0 to Int.MAX_VALUE), 1),
            Triple(mapOf(Int.MAX_VALUE to Int.MAX_VALUE, 0 to 1),
                mapOf(Int.MAX_VALUE to Int.MAX_VALUE, 1 to 1), -1),
        )) {
            val a = Monomial.of(left)
            val b = Monomial.of(right)

            assertEquals(expected, a.compareTo(b))
            assertEquals(-expected, b.compareTo(a))
        }
    }

    @Test
    fun `canonical monomials retain equality and hashes across insertion order and zero powers`() {
        for ((powers, equivalent) in listOf(
            mapOf(5 to 3, 0 to 2) to mapOf(0 to 2, 5 to 3),
            mapOf(0 to 2, 3 to 0, 5 to 3) to mapOf(5 to 3, 0 to 2),
            mapOf(9 to 0) to emptyMap(),
        )) {
            val a = Monomial.of(powers)
            val b = Monomial.of(equivalent)

            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
            assertEquals(0, a.compareTo(b))
        }
        assertEquals(Monomial.ONE.hashCode(), Monomial.of(emptyMap()).hashCode())
    }

    @Test
    fun `ordered monomial hashes distinguish exchanged coordinate and degree roles`() {
        for ((coordinate, degree) in listOf(1 to 2, 3 to 5)) {
            val a = Monomial.of(mapOf(coordinate to degree))
            val b = Monomial.of(mapOf(degree to coordinate))

            assertNotEquals(a, b)
            assertNotEquals(a.hashCode(), b.hashCode())
        }
    }

    @Test
    fun `monomial variables expose fresh ascending views`() {
        val monomial = Monomial.of(mapOf(9 to 1, 2 to 3, 6 to 2))

        val first = monomial.variables
        val second = monomial.variables

        assertEquals(listOf(2, 6, 9), first)
        assertEquals(first, second)
        assertNotSame(first, second)
    }

    @Test
    fun `canonical term ordering preserves coefficients at sparse extreme coordinates`() {
        val low = Monomial.of(mapOf(0 to Int.MAX_VALUE))
        val high = Monomial.of(mapOf(Int.MAX_VALUE to 1))
        val polynomial = Polynomial.of(listOf(
            PolynomialTerm(high, fraction(3)),
            PolynomialTerm(Monomial.ONE, fraction(1)),
            PolynomialTerm(low, fraction(2)),
        ))

        val terms = polynomial.terms

        assertEquals(listOf(Monomial.ONE, low, high), terms.map { it.monomial })
        assertEquals(listOf(fraction(1), fraction(2), fraction(3)), terms.map { it.coefficient })
    }

    @Test
    fun `monomial multiplication rejects exponent overflow without changing its inputs`() {
        val left = Monomial.of(mapOf(0 to Int.MAX_VALUE))
        val right = Monomial.of(mapOf(0 to 1))

        assertFailsWith<IllegalArgumentException> { left.times(right) }

        assertEquals(Int.MAX_VALUE, left.exponent(0))
        assertEquals(1, right.exponent(0))
    }

    @Test
    fun `prepared multiplication publishes only the completed product`() {
        val value = arithmetic.power(arithmetic.add(x, y), 17)
        val pending = arithmetic.prepareMultiply(value, value)

        assertNull(pending.next())
        var result: Polynomial? = null
        while (result == null) result = pending.next()

        assertEquals(arithmetic.multiply(value, value), result)
    }

    @Test
    fun `prepared subresultants retain exact shared factor coefficients`() {
        val left = arithmetic.multiply(
            arithmetic.multiply(arithmetic.subtract(x, constant(1)), arithmetic.subtract(x, constant(2))),
            arithmetic.subtract(x, constant(3)),
        )
        val right = arithmetic.multiply(arithmetic.subtract(x, constant(2)),
            arithmetic.subtract(arithmetic.scale(x, fraction(2)), constant(1)))
        val pending = arithmetic.preparePrincipalSubresultants(left, right, 0)
        var result: List<Polynomial>? = null
        while (result == null) result = pending.next()

        assertEquals(listOf(Polynomial.ZERO, constant(5)), result)
    }

    @Test
    fun `prepared subresultants charge each native operation once`() {
        val left = arithmetic.subtract(arithmetic.power(y, 3), x)
        val right = arithmetic.subtract(arithmetic.power(y, 2), arithmetic.add(x, Polynomial.ONE))
        val charges = LongArray(2)
        for (index in charges.indices) {
            val meter = object : Cancellation, WorkMeter {
                override fun isCancelled(): Boolean = false
                override fun workMeter(): WorkMeter = this
                override fun charge(units: Long) { charges[index] += units }
            }
            val counted = PolynomialArithmetic(meter)
            if (index == 0) counted.principalSubresultants(left, right, 1)
            else {
                val pending = counted.preparePrincipalSubresultants(left, right, 1)
                var result: List<Polynomial>? = null
                while (result == null) result = pending.next()
            }
        }

        assertEquals(charges[0], charges[1])
    }

    @Test
    fun `solve cancellation terminates prepared subresultants`() {
        var stopped = false
        val counted = PolynomialArithmetic(Cancellation { stopped })
        val pending = counted.preparePrincipalSubresultants(arithmetic.power(x, 3), arithmetic.add(x, y), 0)
        assertNull(pending.next())
        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
    }

    @Test
    fun `terms merge and cancel independent of producer mutation`() {
        val powers = mutableMapOf(0 to 2)
        val monomial = Monomial.of(powers)
        val equivalent = Monomial.of(mapOf(0 to 2))
        val terms = mutableListOf(PolynomialTerm(monomial, BigFraction.ONE))
        val polynomial = Polynomial.of(terms)

        powers[0] = 7
        powers[5] = 1
        terms.clear()

        assertEquals(2, polynomial.degree(0))
        assertEquals(equivalent, monomial)
        assertEquals(equivalent.hashCode(), monomial.hashCode())
        assertEquals(0, equivalent.compareTo(monomial))
        assertEquals(listOf(0), monomial.variables)
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
        val right = arithmetic.multiply(
            arithmetic.subtract(x, constant(2)),
            arithmetic.subtract(arithmetic.scale(x, fraction(2)), constant(1)),
        )

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

    @Test
    fun `dense expansion declines before degree overflow or allocation`() {
        val huge = Polynomial.of(listOf(PolynomialTerm(Monomial.of(mapOf(0 to Int.MAX_VALUE)), BigFraction.ONE)))

        val failure = assertFailsWith<PolynomialResourceLimit> { arithmetic.coefficients(huge, 0) }

        assertEquals(PolynomialResource.DENSE_COEFFICIENTS, failure.resource)
    }

    @Test
    fun `projection matrix respects the invocation allocation limit`() {
        val bounded = PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 3))

        val failure = assertFailsWith<PolynomialResourceLimit> { bounded.resultant(x, arithmetic.add(x, y), 0) }

        assertEquals(PolynomialResource.MATRIX_CELLS, failure.resource)
    }

    private fun fraction(value: Long): BigFraction = BigFraction.ofLong(value)
    private fun constant(value: Long): Polynomial = Polynomial.constant(fraction(value))
}
