package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AffineLineEvaluationTest {
    private val polynomial = PolynomialArithmetic()
    private val algebraic = AlgebraicArithmetic(polynomial)

    @Test
    fun `line signs should retain correlation between opposite irrational coordinates`() {
        val source = polynomial.add(
            polynomial.power(Polynomial.variable(0), 2),
            polynomial.multiply(Polynomial.variable(0), Polynomial.variable(1)),
        )
        val line = AffineLineEvaluation(
            listOf(BigFraction.ONE, BigFraction.MINUS_ONE),
            listOf(BigFraction.ofLong(2), BigFraction.ofLong(-2)),
            algebraic,
        )
        val point = line.at(algebraic.roots(RationalPolynomial.of(-2, 0, 1)).last())

        val sign = point.sign(source)

        assertEquals(0, sign)
    }

    @Test
    fun `line capture should own its rational affine coordinates`() {
        val base = mutableListOf(BigFraction.ONE, BigFraction.MINUS_ONE)
        val direction = mutableListOf(BigFraction.ofLong(2), BigFraction.ofLong(-2))
        val line = AffineLineEvaluation(base, direction, algebraic)
        val source = polynomial.add(Polynomial.variable(0), Polynomial.variable(1))
        base[0] = BigFraction.ofLong(7)
        direction[1] = BigFraction.ofLong(3)

        val point = line.at(algebraic.roots(RationalPolynomial.of(-2, 0, 1)).last())

        assertEquals(0, point.sign(source))
    }

    @Test
    fun `separate lines should retain their own polynomial images`() {
        val source = polynomial.subtract(
            polynomial.power(Polynomial.variable(0), 2),
            Polynomial.constant(BigFraction.ofLong(2)),
        )
        val parameter = algebraic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val first = AffineLineEvaluation(listOf(BigFraction.ZERO), listOf(BigFraction.ONE), algebraic)
        val second = AffineLineEvaluation(listOf(BigFraction.ONE), listOf(BigFraction.ONE), algebraic)
        first.at(parameter).sign(source)

        val sign = second.at(parameter).sign(source)

        assertEquals(1, sign)
    }

    @Test
    fun `line points should bind signs to their own exact parameter`() {
        val source = polynomial.subtract(
            polynomial.power(Polynomial.variable(0), 2),
            Polynomial.constant(BigFraction.ofLong(2)),
        )
        val line = AffineLineEvaluation(listOf(BigFraction.ZERO), listOf(BigFraction.ONE), algebraic)
        line.at(algebraic.roots(RationalPolynomial.of(-2, 0, 1)).last()).sign(source)
        val point = line.at(algebraic.roots(RationalPolynomial.of(-3, 0, 1)).last())

        val sign = point.sign(source)

        assertEquals(1, sign)
    }

    @Test
    fun `completed line images should fit the remaining caller work`() {
        val source = polynomial.power(polynomial.add(Polynomial.variable(0), Polynomial.variable(1)), 8)
        var spent = 0L
        var limit = Long.MAX_VALUE
        val meter = WorkMeter { spent += it }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = meter
        }
        val counted = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
        val line = AffineLineEvaluation(
            listOf(BigFraction.ONE, BigFraction.MINUS_ONE),
            listOf(BigFraction.ofLong(2), BigFraction.ofLong(-2)),
            counted,
        )
        val point = line.at(algebraic.roots(RationalPolynomial.of(-2, 0, 1)).last())
        point.sign(source)
        limit = spent + 64

        val sign = point.sign(source)

        assertEquals(0, sign)
    }

    @Test
    fun `completed line images should still obey caller cancellation`() {
        var cancelled = false
        val counted = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { cancelled }))
        val line = AffineLineEvaluation(listOf(BigFraction.ZERO), listOf(BigFraction.ONE), counted)
        val point = line.at(algebraic.roots(RationalPolynomial.of(-2, 0, 1)).last())
        val source = polynomial.power(Polynomial.variable(0), 2)
        point.sign(source)
        cancelled = true

        assertFailsWith<PolynomialInterrupted> { point.sign(source) }
    }

    @Test
    fun `line images should retain the caller dense coefficient limit`() {
        val counted = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxDenseCoefficients = 2)))
        val line = AffineLineEvaluation(listOf(BigFraction.ZERO), listOf(BigFraction.ONE), counted)
        val point = line.at(algebraic.roots(RationalPolynomial.of(-2, 0, 1)).last())
        val source = polynomial.power(Polynomial.variable(0), 2)

        val failure = assertFailsWith<PolynomialResourceLimit> { point.sign(source) }

        assertEquals(PolynomialResource.DENSE_COEFFICIENTS, failure.resource)
    }

    @Test
    fun `line images should reject coordinates outside the captured source dimension`() {
        val point = AffineLineEvaluation(listOf(BigFraction.ZERO), listOf(BigFraction.ONE), algebraic)
            .at(algebraic.rational(BigFraction.ONE))

        assertFailsWith<IllegalArgumentException> { point.sign(Polynomial.variable(1)) }
    }
}
