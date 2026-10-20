package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RationalPolynomialTest {
    private val arithmetic = RationalPolynomialArithmetic()

    @Test
    fun `square free decomposition should retain each root multiplicity`() {
        val a = RationalPolynomial.of(-1, 1)
        val b = RationalPolynomial.of(2, 1)
        val polynomial = arithmetic.multiply(
            arithmetic.multiply(a, a),
            arithmetic.multiply(arithmetic.multiply(b, b), b),
        )

        val factors = arithmetic.squareFreeFactors(polynomial)

        assertEquals(listOf(SquareFreeFactor(a, 2), SquareFreeFactor(b, 3)), factors)
    }

    @Test
    fun `isolation should count repeated and rational roots once`() {
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
    fun `zero polynomial should not masquerade as a finite empty root list`() {
        assertFailsWith<IllegalArgumentException> { arithmetic.isolate(RationalPolynomial.ZERO) }
    }

    @Test
    fun `refinement should preserve an irrational root in a narrower interval`() {
        val polynomial = RationalPolynomial.of(-2, 0, 1)
        val initial = arithmetic.isolate(polynomial).last()

        val refined = arithmetic.refine(polynomial, initial)

        assertTrue(refined.upper - refined.lower < initial.upper - initial.lower)
        assertEquals(1, arithmetic.countOpen(arithmetic.sturm(polynomial), refined))
    }

    @Test
    fun `root count should exclude roots on either endpoint`() {
        val polynomial = RationalPolynomial.of(0, -1, 0, 1)

        val count = arithmetic.countOpen(
            arithmetic.sturm(polynomial),
            RationalInterval(BigFraction.MINUS_ONE, BigFraction.ONE),
        )

        assertEquals(1, count)
    }

    @Test
    fun `optional contraction should preserve isolated roots in narrower intervals`() {
        for ((polynomial, lower, upper) in listOf(
            Triple(RationalPolynomial.of(-2, 0, 1), 1L, 2L),
            Triple(RationalPolynomial.of(-2, 0, 1), -2L, -1L),
            Triple(RationalPolynomial.of(4, 0, -2), 1L, 2L),
            Triple(RationalPolynomial.of(-2, 0, 0, 1), 1L, 2L),
        )) {
            val initial = RationalInterval(BigFraction.ofLong(lower), BigFraction.ofLong(upper))

            val contracted = assertNotNull(arithmetic.tryContractRoot(polynomial, initial))

            assertTrue(contracted.lower >= initial.lower)
            assertTrue(contracted.upper <= initial.upper)
            val width = contracted.upper - contracted.lower
            assertTrue(width + width <= initial.upper - initial.lower)
            assertEquals(1, arithmetic.countOpen(arithmetic.sturm(polynomial), contracted))
        }
    }

    @Test
    fun `optional contraction should decline derivative enclosures containing zero`() {
        for ((polynomial, lower, upper) in listOf(
            Triple(RationalPolynomial.of(-2, 0, 1), 0L, 2L),
            Triple(RationalPolynomial.of(4, 0, -4, 0, 1), 1L, 2L),
        )) {
            val interval = RationalInterval(BigFraction.ofLong(lower), BigFraction.ofLong(upper))

            val contracted = arithmetic.tryContractRoot(polynomial, interval)

            assertNull(contracted)
        }
    }

    @Test
    fun `optional contraction should retain exact midpoint roots`() {
        val polynomial = RationalPolynomial.of(-1, 0, 1)
        val interval = RationalInterval(BigFraction.ZERO, BigFraction.ofLong(2))

        val contracted = arithmetic.tryContractRoot(polynomial, interval)

        assertEquals(RationalInterval(BigFraction.ONE, BigFraction.ONE), contracted)
    }

    @Test
    fun `optional contraction should obey caller cancellation`() {
        val cancelled = RationalPolynomialArithmetic(PolynomialArithmetic(Cancellation { true }))
        val interval = RationalInterval(BigFraction.ONE, BigFraction.ofLong(2))

        assertFailsWith<PolynomialInterrupted> {
            cancelled.tryContractRoot(RationalPolynomial.of(-2, 0, 1), interval)
        }
    }
}
