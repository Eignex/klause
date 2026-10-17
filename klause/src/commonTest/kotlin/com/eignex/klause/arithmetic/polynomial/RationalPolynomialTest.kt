package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `alternating root refinements should fit the remaining caller work`() {
        var spent = 0L
        var limit = Long.MAX_VALUE
        val meter = WorkMeter { spent += it }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = meter
        }
        val counted = RationalPolynomialArithmetic(PolynomialArithmetic(cancellation))
        val first = RationalPolynomial.of(-2, 0, 0, 0, 1)
        val second = RationalPolynomial.of(-3, 0, 0, 0, 1)
        val initial = RationalInterval(BigFraction.ONE, BigFraction.ofLong(2))
        var a = counted.refine(first, initial)
        var b = counted.refine(second, initial)
        limit = spent + 4000

        repeat(24) {
            a = counted.refine(first, a)
            b = counted.refine(second, b)
        }

        assertTrue(a.upper - a.lower < initial.upper - initial.lower)
        assertTrue(b.upper - b.lower < initial.upper - initial.lower)
        assertEquals(1, arithmetic.countOpen(arithmetic.sturm(first), a))
        assertEquals(1, arithmetic.countOpen(arithmetic.sturm(second), b))
    }

    @Test
    fun `repeated root refinement should retain its distinct root`() {
        val polynomial = RationalPolynomial.of(4, 0, -4, 0, 1)
        val initial = RationalInterval(BigFraction.ONE, BigFraction.ofLong(2))
        var interval = initial

        repeat(12) { interval = arithmetic.refine(polynomial, interval) }

        assertTrue(interval.upper - interval.lower < initial.upper - initial.lower)
        assertEquals(1, arithmetic.countOpen(arithmetic.sturm(polynomial), interval))
    }

    @Test
    fun `completed refinement sequences should obey caller cancellation`() {
        var cancelled = false
        val counted = RationalPolynomialArithmetic(PolynomialArithmetic(Cancellation { cancelled }))
        val polynomial = RationalPolynomial.of(-2, 0, 1)
        val interval = counted.refine(polynomial, RationalInterval(BigFraction.ONE, BigFraction.ofLong(2)))
        cancelled = true

        assertFailsWith<PolynomialInterrupted> { counted.refine(polynomial, interval) }
    }

    @Test
    fun `separate refinement contexts should retain their own dense limits`() {
        val polynomial = RationalPolynomial.of(-2, 0, 0, 0, 1)
        val interval = arithmetic.refine(polynomial, RationalInterval(BigFraction.ONE, BigFraction.ofLong(2)))
        val limited = RationalPolynomialArithmetic(
            PolynomialArithmetic(limits = PolynomialLimits(maxDenseCoefficients = 2)),
        )

        val failure = assertFailsWith<PolynomialResourceLimit> { limited.refine(polynomial, interval) }

        assertEquals(PolynomialResource.DENSE_COEFFICIENTS, failure.resource)
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
}
