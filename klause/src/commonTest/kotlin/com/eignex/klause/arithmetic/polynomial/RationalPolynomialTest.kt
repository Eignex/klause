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
    fun `rational polynomial images should retain exact signed fractional values`() {
        val half = BigFraction.ofLong(2).reciprocal()
        val polynomial = RationalPolynomial(listOf(half, BigFraction.ofLong(3).reciprocal(),
            BigFraction.ofLong(6).reciprocal()))
        val sharedDenominators = RationalPolynomial(listOf(BigFraction.ofLong(6).reciprocal(),
            BigFraction.ofLong(-10).reciprocal(), BigFraction.ofLong(15).reciprocal()))
        for ((source, point, expected) in listOf(
            Triple(polynomial, half, BigFraction.ofLong(17) * BigFraction.ofLong(24).reciprocal()),
            Triple(polynomial, half.negated(), BigFraction.ofLong(3) * BigFraction.ofLong(8).reciprocal()),
            Triple(sharedDenominators, BigFraction.ofLong(3) * half, BigFraction.ofLong(6).reciprocal()),
        )) {
            val result = arithmetic.evaluate(source, point)

            assertEquals(expected, result)
        }
    }

    @Test
    fun `rational polynomial cancellation should return exact zero`() {
        val polynomial = RationalPolynomial(listOf(BigFraction.ofLong(-6).reciprocal(),
            BigFraction.ofLong(7) * BigFraction.ofLong(30).reciprocal(), BigFraction.ofLong(5).reciprocal()))

        val result = arithmetic.evaluate(polynomial, BigFraction.ofLong(2).reciprocal())

        assertEquals(BigFraction.ZERO, result)
    }

    @Test
    fun `constant polynomial images should be independent of the rational argument`() {
        for (constant in listOf(BigFraction.ZERO, BigFraction.ofLong(3).reciprocal())) {
            val polynomial = RationalPolynomial(listOf(constant))

            val result = arithmetic.evaluate(polynomial,
                BigFraction.ofLong(-5) * BigFraction.ofLong(7).reciprocal())

            assertEquals(constant, result)
        }
    }

    @Test
    fun `owned polynomial images should retain narrow caller admission`() {
        val polynomial = RationalPolynomial.of(-1, 0, 0, 0, 1)
        val caller = RationalPolynomialArithmetic(PolynomialArithmetic(limits = PolynomialLimits(
            maxDenseCoefficients = 1, maxMatrixCells = 0,
        )))

        val result = caller.evaluate(polynomial, BigFraction.ofLong(2).reciprocal())

        assertEquals(BigFraction.ofLong(-15) * BigFraction.ofLong(16).reciprocal(), result)
    }

    @Test
    fun `rational images should observe caller cancellation for every polynomial shape`() {
        val caller = RationalPolynomialArithmetic(PolynomialArithmetic(Cancellation { true }))
        for (polynomial in listOf(RationalPolynomial.ZERO, RationalPolynomial.ONE,
            RationalPolynomial.of(-1, 0, 1))
        ) {
            assertFailsWith<PolynomialInterrupted> {
                caller.evaluate(polynomial, BigFraction.ofLong(2).reciprocal())
            }
        }
    }

    @Test
    fun `caller work exhaustion should withhold rational polynomial images`() {
        val polynomial = RationalPolynomial.of(-1, 0, 0, 0, 1)
        var spent = 0L
        val meter = WorkMeter { spent += it }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= 3
            override fun workMeter(): WorkMeter = meter
        }
        val caller = RationalPolynomialArithmetic(PolynomialArithmetic(cancellation))

        assertFailsWith<PolynomialInterrupted> {
            caller.evaluate(polynomial, BigFraction.ofLong(2).reciprocal())
        }
    }

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
}
