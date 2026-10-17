package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialTest {
    @Test
    fun `canonical terms exclude cancelled monomials from coordinate metadata`() {
        val low = Monomial.of(mapOf(0 to 2))
        val cancelled = Monomial.of(mapOf(7 to 3))
        val high = Monomial.of(mapOf(3 to 4))
        val expected = listOf(PolynomialTerm(Monomial.ONE, BigFraction.ONE),
            PolynomialTerm(low, BigFraction.ofLong(2)), PolynomialTerm(high, BigFraction.ofLong(3)))

        val polynomial = Polynomial.of(listOf(expected[2], PolynomialTerm(cancelled, BigFraction.ONE),
            expected[0], expected[1], PolynomialTerm(cancelled, BigFraction.MINUS_ONE)))

        assertEquals(expected, polynomial.terms)
        assertEquals(listOf(0, 3), polynomial.variables)
        assertEquals(3, polynomial.mainVariable)
        assertEquals(0, polynomial.degree(7))
        assertFalse(polynomial.containsVariable(7))
    }

    @Test
    fun `coordinate degrees preserve sparse extreme IDs and absent variable values`() {
        val polynomial = Polynomial.of(listOf(
            PolynomialTerm(Monomial.of(mapOf(0 to 2, Int.MAX_VALUE to Int.MAX_VALUE)), BigFraction.ONE),
            PolynomialTerm(Monomial.of(mapOf(3 to 4)), BigFraction.ONE),
        ))

        assertEquals(listOf(0, 3, Int.MAX_VALUE), polynomial.variables)
        assertEquals(Int.MAX_VALUE, polynomial.mainVariable)
        assertEquals(2, polynomial.degree(0))
        assertEquals(4, polynomial.degree(3))
        assertEquals(Int.MAX_VALUE, polynomial.degree(Int.MAX_VALUE))
        assertEquals(0, polynomial.degree(1))
        assertTrue(polynomial.containsVariable(Int.MAX_VALUE))
        for ((value, degree) in listOf(Polynomial.ZERO to -1, Polynomial.ONE to 0)) {
            assertTrue(value.variables.isEmpty())
            assertNull(value.mainVariable)
            assertEquals(degree, value.degree(0))
            assertEquals(degree, value.degree(Int.MAX_VALUE))
            assertFalse(value.containsVariable(0))
        }
    }

    @Test
    fun `term and coordinate views retain independent containers`() {
        val expected = listOf(PolynomialTerm(Monomial.ONE, BigFraction.ONE),
            PolynomialTerm(Monomial.of(mapOf(0 to 2)), BigFraction.ofLong(2)),
            PolynomialTerm(Monomial.of(mapOf(3 to 4)), BigFraction.ofLong(3)))
        val polynomial = Polynomial.of(expected)
        val terms = polynomial.terms as MutableList<PolynomialTerm>
        val variables = polynomial.variables as MutableList<Int>

        terms.clear()
        variables.clear()

        assertEquals(expected, polynomial.terms)
        assertEquals(listOf(0, 3), polynomial.variables)
        assertEquals(Polynomial.of(expected), polynomial)
        assertEquals(Polynomial.of(expected).hashCode(), polynomial.hashCode())
    }
}
