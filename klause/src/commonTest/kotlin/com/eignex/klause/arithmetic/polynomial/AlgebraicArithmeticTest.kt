package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlgebraicArithmeticTest {
    private val polynomial = PolynomialArithmetic()
    private val arithmetic = AlgebraicArithmetic(polynomial)

    @Test
    fun `distinct irrational roots have exact order and polynomial signs`() {
        val roots = arithmetic.roots(RationalPolynomial.of(-2, 0, 1))

        assertEquals(-1, arithmetic.compare(roots[0], roots[1]))
        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-2, 0, 1), roots[1]))
        assertEquals(1, arithmetic.signAt(RationalPolynomial.of(-1, 1), roots[1]))
        assertEquals(-1, arithmetic.signAt(RationalPolynomial.of(-2, 1), roots[1]))
    }

    @Test
    fun `rational sector samples lie strictly between irrational boundaries`() {
        val lower = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val upper = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()

        val sample = arithmetic.rational(arithmetic.sampleBetween(lower, upper))

        assertEquals(-1, arithmetic.compare(lower, sample))
        assertEquals(-1, arithmetic.compare(sample, upper))
    }

    @Test
    fun `unbounded sector samples lie beyond the finite boundary`() {
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()

        assertEquals(-1, arithmetic.compare(arithmetic.rational(arithmetic.sampleBetween(null, root)), root))
        assertEquals(1, arithmetic.compare(arithmetic.rational(arithmetic.sampleBetween(root, null)), root))
    }

    @Test
    fun `common irrational roots compare equal across different defining polynomials`() {
        val first = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val second = arithmetic.roots(RationalPolynomial.of(2, -2, -1, 1)).last()

        assertEquals(0, arithmetic.compare(first, second))
    }

    @Test
    fun `irrational arithmetic certifies exact rational cancellation`() {
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()

        val square = arithmetic.multiply(root, root)

        assertEquals(BigFraction.ofLong(2), square.rational)
    }

    @Test
    fun `different irrational conjugates sum to exact zero`() {
        val roots = arithmetic.roots(RationalPolynomial.of(-2, 0, 1))

        val sum = arithmetic.add(roots[0], roots[1])

        assertEquals(0, arithmetic.sign(sum))
    }

    @Test
    fun `independent irrational addition retains its annihilating identity`() {
        val a = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val b = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()

        val sum = arithmetic.add(a, b)

        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(1, 0, -10, 0, 1), sum))
        assertEquals(1, arithmetic.sign(sum))
    }

    @Test
    fun `reciprocal of an irrational value has exact multiplicative identity`() {
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()

        val product = arithmetic.multiply(root, arithmetic.reciprocal(root))

        assertEquals(0, arithmetic.compare(product, arithmetic.rational(BigFraction.ONE)))
    }

    @Test
    fun `lifting isolates exact roots above an irrational coordinate`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val relation = polynomial.subtract(polynomial.power(Polynomial.variable(1), 2), Polynomial.variable(0))
        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to x))

        val roots = arithmetic.roots(specialized)

        assertEquals(2, roots.size)
        for (root in roots) assertEquals(0, arithmetic.compare(arithmetic.power(root, 2), x))
        assertEquals(-1, arithmetic.sign(roots[0]))
        assertEquals(1, arithmetic.sign(roots[1]))
    }

    @Test
    fun `algebraic lifting filters roots from other coefficient conjugates`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val relation = polynomial.subtract(Polynomial.variable(1), Polynomial.variable(0))

        val roots = arithmetic.roots(arithmetic.specialize(relation, 1, mapOf(0 to x)))

        assertEquals(1, roots.size)
        assertEquals(0, arithmetic.compare(roots.single(), x))
    }

    @Test
    fun `specialization trims vanished leading coefficients exactly`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val leading = polynomial.subtract(
            polynomial.power(Polynomial.variable(0), 2), Polynomial.constant(BigFraction.ofLong(2)),
        )
        val relation = polynomial.add(
            polynomial.multiply(leading, polynomial.power(Polynomial.variable(1), 2)), Polynomial.variable(1),
        )

        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to x))

        assertEquals(1, specialized.degree)
        assertEquals(0, arithmetic.sign(arithmetic.roots(specialized).single()))
    }

    @Test
    fun `nullified specialization remains distinct from an empty root family`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val relation = polynomial.subtract(
            polynomial.power(Polynomial.variable(0), 2), Polynomial.constant(BigFraction.ofLong(2)),
        )

        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to x))

        assertTrue(specialized.isZero)
        assertFailsWith<IllegalArgumentException> { arithmetic.roots(specialized) }
    }

    @Test
    fun `repeated specialized roots appear once`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val linear = polynomial.subtract(Polynomial.variable(1), Polynomial.variable(0))

        val roots = arithmetic.roots(arithmetic.specialize(polynomial.power(linear, 2), 1, mapOf(0 to x)))

        assertEquals(1, roots.size)
        assertEquals(0, arithmetic.compare(roots.single(), x))
    }

    @Test
    fun `lifting evaluates coefficients from multiple earlier irrational coordinates`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val y = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()
        val relation = polynomial.subtract(
            Polynomial.variable(2), polynomial.add(Polynomial.variable(0), Polynomial.variable(1)),
        )

        val roots = arithmetic.roots(arithmetic.specialize(relation, 2, mapOf(0 to x, 1 to y)))

        assertEquals(1, roots.size)
        assertEquals(0, arithmetic.compare(roots.single(), arithmetic.add(x, y)))
    }

    @Test
    fun `polynomial evaluation preserves equality across algebraic coordinates`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val y = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 0, 1)).last()
        val relation = polynomial.subtract(polynomial.power(Polynomial.variable(1), 2), Polynomial.variable(0))

        val value = arithmetic.evaluate(relation, mapOf(0 to x, 1 to y))

        assertEquals(0, arithmetic.sign(value))
    }

    @Test
    fun `lifting removes an unselected zero conjugate of the leading coefficient`() {
        val leading = arithmetic.roots(RationalPolynomial.of(0, -2, 0, 1)).last()
        val relation = arithmetic.polynomial(listOf(arithmetic.rational(BigFraction.MINUS_ONE), leading))

        val roots = arithmetic.roots(relation)

        assertEquals(1, roots.size)
        assertEquals(
            0, arithmetic.compare(arithmetic.multiply(leading, roots.single()), arithmetic.rational(BigFraction.ONE)),
        )
    }

    @Test
    fun `negative irrational square target has no lifted real roots`() {
        val target = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).first()
        val relation = arithmetic.polynomial(listOf(
            arithmetic.negate(target), arithmetic.rational(BigFraction.ZERO), arithmetic.rational(BigFraction.ONE),
        ))

        assertTrue(arithmetic.roots(relation).isEmpty())
    }

    @Test
    fun `owned algebraic metadata survives mutation of producer coefficients`() {
        val coefficients = mutableListOf(BigFraction.ofLong(-2), BigFraction.ZERO, BigFraction.ONE)
        val value = arithmetic.roots(RationalPolynomial(coefficients)).last()

        coefficients[0] = BigFraction.ZERO

        assertEquals(BigFraction.ofLong(-2), value.definingCoefficients[0])
        assertEquals(2, value.rootIndex)
        assertTrue(value.lowerBound < value.upperBound)
    }

    @Test
    fun `cancelled algebraic lifting publishes no partial root list`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val specialized = arithmetic.polynomial(listOf(
            arithmetic.negate(x), arithmetic.rational(BigFraction.ZERO), arithmetic.rational(BigFraction.ONE),
        ))
        val cancelled = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { true }))

        assertFailsWith<PolynomialInterrupted> { cancelled.roots(specialized) }
    }
}
