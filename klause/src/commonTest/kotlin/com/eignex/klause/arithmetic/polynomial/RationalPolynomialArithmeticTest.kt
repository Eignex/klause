package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RationalPolynomialArithmeticTest {
    private val arithmetic = RationalPolynomialArithmetic()

    @Test
    fun `square free decomposition retains each root multiplicity`() {
        val a = RationalPolynomial.of(-1, 1)
        val b = RationalPolynomial.of(2, 1)
        val polynomial = arithmetic.multiply(arithmetic.multiply(a, a), arithmetic.multiply(arithmetic.multiply(b, b), b))

        val factors = arithmetic.squareFreeFactors(polynomial)

        assertEquals(listOf(SquareFreeFactor(a, 2), SquareFreeFactor(b, 3)), factors)
    }

    @Test
    fun `isolation counts repeated and rational roots once`() {
        for ((polynomial, expected) in listOf(
            RationalPolynomial.of(0, -1, 0, 1) to 3,
            RationalPolynomial.of(1, -2, 1) to 1,
            RationalPolynomial.of(-2, 0, 1) to 2,
            RationalPolynomial.of(1, 0, 1) to 0,
            RationalPolynomial.of(7) to 0,
        )) {
            val roots = arithmetic.isolate(polynomial)

            assertEquals(expected, roots.size)
            for (root in roots) {
                if (root.isPoint) {
                    assertEquals(BigFraction.ZERO, arithmetic.evaluate(polynomial, root.lower))
                } else {
                    assertEquals(1, arithmetic.countOpen(arithmetic.sturm(polynomial), root))
                }
            }
            assertTrue(roots.zipWithNext().all { (left, right) -> left.upper <= right.lower })
        }
    }

    @Test
    fun `zero polynomial cannot masquerade as a finite empty root list`() {
        assertFailsWith<IllegalArgumentException> { arithmetic.isolate(RationalPolynomial.ZERO) }
    }

    @Test
    fun `refinement preserves an irrational root in a narrower interval`() {
        val polynomial = RationalPolynomial.of(-2, 0, 1)
        val initial = arithmetic.isolate(polynomial).last()

        val refined = arithmetic.refine(polynomial, initial)

        assertTrue(refined.upper - refined.lower < initial.upper - initial.lower)
        assertEquals(1, arithmetic.countOpen(arithmetic.sturm(polynomial), refined))
    }

    @Test
    fun `root count excludes roots on either endpoint`() {
        val polynomial = RationalPolynomial.of(0, -1, 0, 1)

        val count = arithmetic.countOpen(arithmetic.sturm(polynomial), RationalInterval(BigFraction.MINUS_ONE, BigFraction.ONE))

        assertEquals(1, count)
    }
}
