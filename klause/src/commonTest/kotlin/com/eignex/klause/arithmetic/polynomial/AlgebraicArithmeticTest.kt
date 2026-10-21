package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlgebraicArithmeticTest {
    private val polynomial = PolynomialArithmetic()
    private val arithmetic = AlgebraicArithmetic(polynomial)

    @Test
    fun `algebraic linear lifting retains the selected annihilator root and its bounds`() {
        val coefficient = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val boundaries = arithmetic.roots(RationalPolynomial.of(-2, 0, 1))
        for (negative in listOf(false, true)) {
            val constant = if (negative) arithmetic.negate(coefficient) else coefficient
            val source = arithmetic.polynomial(listOf(constant, arithmetic.rational(BigFraction.ONE)))
            val expected = boundaries[if (negative) 1 else 0]

            val roots = arithmetic.roots(source)

            assertEquals(1, roots.size)
            val root = roots.single()
            assertEquals(0, arithmetic.compare(expected, root))
            assertEquals(expected.definingCoefficients, root.definingCoefficients)
            assertEquals(expected.rootIndex, root.rootIndex)
            assertEquals(expected.lowerBound, root.lowerBound)
            assertEquals(expected.upperBound, root.upperBound)
        }
    }

    @Test
    fun `algebraic quadratic lifting retains both selected roots and their annihilator bounds`() {
        val coefficient = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val quarter = BigFraction.ofLong(4).reciprocal()
        val annihilator = RationalPolynomial(listOf(quarter * quarter, BigFraction.ZERO,
            BigFraction.ofLong(-3) * BigFraction.ofLong(2).reciprocal(), BigFraction.ZERO, BigFraction.ONE))
        val boundaries = arithmetic.roots(annihilator)
        for (negative in listOf(false, true)) {
            val linear = if (negative) arithmetic.negate(coefficient) else coefficient
            val source = arithmetic.polynomial(listOf(arithmetic.rational(quarter), linear,
                arithmetic.rational(BigFraction.ONE)))
            val expected = if (negative) boundaries.takeLast(2) else boundaries.take(2)

            val roots = arithmetic.roots(source)

            assertEquals(2, roots.size)
            for (index in roots.indices) {
                assertEquals(0, arithmetic.compare(expected[index], roots[index]))
                assertEquals(expected[index].definingCoefficients, roots[index].definingCoefficients)
                assertEquals(expected[index].rootIndex, roots[index].rootIndex)
                assertEquals(expected[index].lowerBound, roots[index].lowerBound)
                assertEquals(expected[index].upperBound, roots[index].upperBound)
            }
        }
    }

    @Test
    fun `work exhaustion withholds a degree complete algebraic root family`() {
        val coefficient = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val source = arithmetic.polynomial(listOf(coefficient, arithmetic.rational(BigFraction.ONE)))
        var spent = 0L
        var limit = Long.MAX_VALUE
        val meter = WorkMeter { spent += it }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = meter
        }
        val completed = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
        assertEquals(1, completed.roots(source).size)
        limit = spent - 1
        spent = 0
        val limited = AlgebraicArithmetic(PolynomialArithmetic(cancellation))

        assertFailsWith<PolynomialInterrupted> { limited.roots(source) }
    }

    @Test
    fun `completed root counts should fit negative affine transforms in remaining caller work`() {
        var spent = 0L
        var limit = Long.MAX_VALUE
        val meter = WorkMeter { spent += it }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = meter
        }
        val counted = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
        val root = counted.roots(RationalPolynomial.of(-2, 0, 1)).last()
        limit = spent + 64

        val negative = counted.negate(root)

        assertEquals(1, negative.rootIndex)
        assertEquals(-1, arithmetic.sign(negative))
    }

    @Test
    fun `negative affine images should retain root order for distinct defining polynomials`() {
        val first = arithmetic.roots(RationalPolynomial.of(-2, 0, -1, 0, 1)).last()
        val second = arithmetic.roots(RationalPolynomial.of(6, 0, -5, 0, 1)).last()

        val negatives = listOf(first, second).map(arithmetic::negate)

        for (negative in negatives) assertEquals(1, negative.rootIndex)
    }

    @Test
    fun `rational coefficient lifting should return only actual real roots`() {
        val source = RationalPolynomial.of(-2, 0, -1, 0, 1)
        val lifted = arithmetic.polynomial(source.coefficients.map(arithmetic::rational))

        val roots = arithmetic.roots(lifted)

        assertEquals(2, roots.size)
        assertEquals(-1, arithmetic.sign(roots[0]))
        assertEquals(1, arithmetic.sign(roots[1]))
        for (root in roots) assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-2, 0, 1), root))
    }

    @Test
    fun `rational coefficient lifting should retain the caller dense limit`() {
        val source = arithmetic.polynomial(RationalPolynomial.of(-2, 0, 1).coefficients.map(arithmetic::rational))
        val counted = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxDenseCoefficients = 2)))

        val failure = assertFailsWith<PolynomialResourceLimit> { counted.roots(source) }

        assertEquals(PolynomialResource.DENSE_COEFFICIENTS, failure.resource)
    }

    @Test
    fun `rational point images should evaluate mixed source monomials exactly`() {
        val source = polynomial.add(
            polynomial.multiply(Polynomial.variable(0), Polynomial.variable(1)),
            polynomial.power(Polynomial.variable(0), 2),
        )
        val point = arithmetic.pointEvaluation(
            mapOf(
                0 to arithmetic.rational(BigFraction.ofLong(3) * BigFraction.ofLong(2).reciprocal()),
                1 to arithmetic.rational(BigFraction.ofLong(5) * BigFraction.ofLong(3).reciprocal()),
            ),
        )

        val value = point.evaluate(source)

        assertEquals(BigFraction.ofLong(19) * BigFraction.ofLong(4).reciprocal(), value.rational)
    }

    @Test
    fun `rational point images should use captured values after caller mutation`() {
        val coordinates = mutableMapOf(0 to arithmetic.rational(BigFraction.ofLong(2)))
        val point = arithmetic.pointEvaluation(coordinates)
        coordinates[0] = arithmetic.rational(BigFraction.ofLong(3))

        val value = point.evaluate(polynomial.power(Polynomial.variable(0), 2))

        assertEquals(BigFraction.ofLong(4), value.rational)
    }

    @Test
    fun `rational point images should reject unrepresentable constant powers before expansion`() {
        val point = arithmetic.pointEvaluation(mapOf(0 to arithmetic.rational(BigFraction.ofLong(4))))
        val source = Polynomial.of(
            listOf(
                PolynomialTerm(Monomial.of(mapOf(0 to Int.MAX_VALUE)), BigFraction.ONE),
            ),
        )

        val failure = assertFailsWith<PolynomialResourceLimit> { point.evaluate(source) }

        assertEquals(PolynomialResource.DEGREE_OVERFLOW, failure.resource)
    }

    @Test
    fun `completed point powers should fit the remaining caller work`() {
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val source = polynomial.add(
            polynomial.power(Polynomial.variable(0), 4),
            polynomial.power(Polynomial.variable(0), 2),
        )
        var spent = 0L
        var limit = Long.MAX_VALUE
        val meter = WorkMeter { spent += it }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = meter
        }
        val counted = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
        val point = counted.pointEvaluation(mapOf(0 to root))
        point.evaluate(source)
        limit = spent + 64

        val result = point.evaluate(source)

        assertEquals(BigFraction.ofLong(6), result.rational)
    }

    @Test
    fun `point capture should own its original algebraic coordinates`() {
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val other = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()
        val coordinates = mutableMapOf(0 to root)
        val point = arithmetic.pointEvaluation(coordinates)
        val source = polynomial.power(Polynomial.variable(0), 2)
        coordinates[0] = other

        val result = point.evaluate(source)

        assertEquals(BigFraction.ofLong(2), result.rational)
        assertEquals(0, arithmetic.compare(root, point.coordinate(0)))
    }

    @Test
    fun `separate point evaluations should retain their own exact powers`() {
        val source = polynomial.power(Polynomial.variable(0), 2)
        val first = arithmetic.pointEvaluation(
            mapOf(0 to arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()),
        )
        val second = arithmetic.pointEvaluation(
            mapOf(0 to arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()),
        )
        first.evaluate(source)

        val result = second.evaluate(source)

        assertEquals(BigFraction.ofLong(3), result.rational)
    }

    @Test
    fun `completed point powers should still obey caller cancellation`() {
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        for (coordinate in listOf(root, arithmetic.rational(BigFraction.ofLong(2)))) {
            var cancelled = false
            val counted = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { cancelled }))
            val point = counted.pointEvaluation(mapOf(0 to coordinate))
            val source = polynomial.power(Polynomial.variable(0), 2)
            point.evaluate(source)
            cancelled = true

            assertFailsWith<PolynomialInterrupted> { point.evaluate(source) }
        }
    }

    @Test
    fun `distinct irrational roots should have exact order and polynomial signs`() {
        val roots = arithmetic.roots(RationalPolynomial.of(-2, 0, 1))

        assertEquals(-1, arithmetic.compare(roots[0], roots[1]))
        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-2, 0, 1), roots[1]))
        assertEquals(1, arithmetic.signAt(RationalPolynomial.of(-1, 1), roots[1]))
        assertEquals(-1, arithmetic.signAt(RationalPolynomial.of(-2, 1), roots[1]))
    }

    @Test
    fun `rational sector samples should lie strictly between irrational boundaries`() {
        val lower = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val upper = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()

        val sample = arithmetic.rational(arithmetic.sampleBetween(lower, upper))

        assertEquals(-1, arithmetic.compare(lower, sample))
        assertEquals(-1, arithmetic.compare(sample, upper))
    }

    @Test
    fun `unbounded sector samples should lie beyond the finite boundary`() {
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()

        assertEquals(-1, arithmetic.compare(arithmetic.rational(arithmetic.sampleBetween(null, root)), root))
        assertEquals(1, arithmetic.compare(arithmetic.rational(arithmetic.sampleBetween(root, null)), root))
    }

    @Test
    fun `common irrational roots should compare equal across different defining polynomials`() {
        val first = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val second = arithmetic.roots(RationalPolynomial.of(2, -2, -1, 1)).last()

        assertEquals(0, arithmetic.compare(first, second))
    }

    @Test
    fun `irrational arithmetic should certify exact rational cancellation`() {
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()

        val square = arithmetic.multiply(root, root)

        assertEquals(BigFraction.ofLong(2), square.rational)
    }

    @Test
    fun `different irrational conjugates should sum to exact zero`() {
        val roots = arithmetic.roots(RationalPolynomial.of(-2, 0, 1))

        val sum = arithmetic.add(roots[0], roots[1])

        assertEquals(0, arithmetic.sign(sum))
    }

    @Test
    fun `independent irrational addition should retain its annihilating identity`() {
        val a = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val b = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()

        val sum = arithmetic.add(a, b)

        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(1, 0, -10, 0, 1), sum))
        assertEquals(1, arithmetic.sign(sum))
    }

    @Test
    fun `reciprocal of an irrational value should have exact multiplicative identity`() {
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()

        val product = arithmetic.multiply(root, arithmetic.reciprocal(root))

        assertEquals(0, arithmetic.compare(product, arithmetic.rational(BigFraction.ONE)))
    }

    @Test
    fun `lifting should isolate exact roots above an irrational coordinate`() {
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
    fun `algebraic lifting should filter roots from other coefficient conjugates`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val relation = polynomial.subtract(Polynomial.variable(1), Polynomial.variable(0))

        val roots = arithmetic.roots(arithmetic.specialize(relation, 1, mapOf(0 to x)))

        assertEquals(1, roots.size)
        assertEquals(0, arithmetic.compare(roots.single(), x))
    }

    @Test
    fun `specialization should trim vanished leading coefficients exactly`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val leading = polynomial.subtract(
            polynomial.power(Polynomial.variable(0), 2),
            Polynomial.constant(BigFraction.ofLong(2)),
        )
        val relation = polynomial.add(
            polynomial.multiply(leading, polynomial.power(Polynomial.variable(1), 2)),
            Polynomial.variable(1),
        )

        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to x))

        assertEquals(1, specialized.degree)
        assertEquals(0, arithmetic.sign(arithmetic.roots(specialized).single()))
    }

    @Test
    fun `nullified specialization should remain distinct from an empty root family`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val relation = polynomial.subtract(
            polynomial.power(Polynomial.variable(0), 2),
            Polynomial.constant(BigFraction.ofLong(2)),
        )

        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to x))

        assertTrue(specialized.isZero)
        assertFailsWith<IllegalArgumentException> { arithmetic.roots(specialized) }
    }

    @Test
    fun `repeated specialized roots should appear once`() {
        val irrational = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        for (x in listOf(irrational, arithmetic.rational(BigFraction.ofLong(2)))) {
            val linear = polynomial.subtract(Polynomial.variable(1), Polynomial.variable(0))

            val roots = arithmetic.roots(arithmetic.specialize(polynomial.power(linear, 2), 1, mapOf(0 to x)))

            assertEquals(1, roots.size)
            assertEquals(0, arithmetic.compare(roots.single(), x))
        }
    }

    @Test
    fun `lifting should evaluate coefficients from multiple earlier irrational coordinates`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val y = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()
        val relation = polynomial.subtract(
            Polynomial.variable(2),
            polynomial.add(Polynomial.variable(0), Polynomial.variable(1)),
        )

        val roots = arithmetic.roots(arithmetic.specialize(relation, 2, mapOf(0 to x, 1 to y)))

        assertEquals(1, roots.size)
        assertEquals(0, arithmetic.compare(roots.single(), arithmetic.add(x, y)))
    }

    @Test
    fun `polynomial evaluation should preserve equality across algebraic coordinates`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val y = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 0, 1)).last()
        val relation = polynomial.subtract(polynomial.power(Polynomial.variable(1), 2), Polynomial.variable(0))

        val value = arithmetic.evaluate(relation, mapOf(0 to x, 1 to y))

        assertEquals(0, arithmetic.sign(value))
    }

    @Test
    fun `lifting should remove an unselected zero conjugate of the leading coefficient`() {
        val leading = arithmetic.roots(RationalPolynomial.of(0, -2, 0, 1)).last()
        val relation = arithmetic.polynomial(listOf(arithmetic.rational(BigFraction.MINUS_ONE), leading))

        val roots = arithmetic.roots(relation)

        assertEquals(1, roots.size)
        assertEquals(
            0,
            arithmetic.compare(arithmetic.multiply(leading, roots.single()), arithmetic.rational(BigFraction.ONE)),
        )
    }

    @Test
    fun `negative irrational square target should have no lifted real roots`() {
        val target = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).first()
        val relation = arithmetic.polynomial(
            listOf(
                arithmetic.negate(target),
                arithmetic.rational(BigFraction.ZERO),
                arithmetic.rational(BigFraction.ONE),
            ),
        )

        assertTrue(arithmetic.roots(relation).isEmpty())
    }

    @Test
    fun `owned algebraic metadata should survive mutation of producer coefficients`() {
        val coefficients = mutableListOf(BigFraction.ofLong(-2), BigFraction.ZERO, BigFraction.ONE)
        val value = arithmetic.roots(RationalPolynomial(coefficients)).last()

        coefficients[0] = BigFraction.ZERO

        assertEquals(BigFraction.ofLong(-2), value.definingCoefficients[0])
        assertEquals(2, value.rootIndex)
        assertTrue(value.lowerBound < value.upperBound)
    }

    @Test
    fun `cancelled algebraic lifting should publish no partial root list`() {
        val x = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val specialized = arithmetic.polynomial(
            listOf(
                arithmetic.negate(x),
                arithmetic.rational(BigFraction.ZERO),
                arithmetic.rational(BigFraction.ONE),
            ),
        )
        val cancelled = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { true }))

        assertFailsWith<PolynomialInterrupted> { cancelled.roots(specialized) }
    }
}
