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
    fun `independent point signs should avoid algebraic image elimination`() {
        val coordinates = mapOf(
            0 to arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last(),
            1 to arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last(),
        )
        val source = polynomial.add(Polynomial.ONE,
            polynomial.add(Polynomial.variable(0), Polynomial.variable(1)))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))

        val sign = caller.signAt(source, coordinates)

        assertEquals(1, sign)
    }

    @Test
    fun `even point powers should retain negative enclosures across zero`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val value = AlgebraicReal(base.definingPolynomial, base.rootIndex,
            RationalInterval(BigFraction.ofLong(-2), BigFraction.ofLong(2)))
        val source = polynomial.negate(polynomial.add(Polynomial.ONE, polynomial.power(Polynomial.variable(0), 2)))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))

        val sign = caller.signAt(source, mapOf(0 to value))

        assertEquals(-1, sign)
    }

    @Test
    fun `odd negative coordinate powers should preserve their order`() {
        val coordinates = mapOf(
            0 to arithmetic.refine(arithmetic.roots(RationalPolynomial.of(2, 0, 0, 1)).single()),
            1 to arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last(),
        )
        val source = polynomial.subtract(polynomial.subtract(polynomial.power(Polynomial.variable(0), 5),
            Polynomial.variable(1)), Polynomial.ONE)
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))

        val sign = caller.signAt(source, coordinates)

        assertEquals(-1, sign)
    }

    @Test
    fun `point signs should retain exact zero when enclosures overlap`() {
        val value = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val source = polynomial.subtract(Polynomial.variable(0), Polynomial.variable(1))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))

        val sign = caller.signAt(source, mapOf(0 to value, 1 to value))

        assertEquals(0, sign)
    }

    @Test
    fun `point signs should not treat an enclosure touching zero as zero`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val value = AlgebraicReal(base.definingPolynomial, base.rootIndex,
            RationalInterval(BigFraction.ZERO, BigFraction.ofLong(2)))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))

        val sign = caller.signAt(Polynomial.variable(0), mapOf(0 to value))

        assertEquals(1, sign)
    }

    @Test
    fun `declined point enclosures should retain admitted scalar evaluation`() {
        val value = arithmetic.refine(arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single())
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(
            maxDenseCoefficients = 1, maxMatrixCells = 0,
        )))

        val sign = caller.signAt(Polynomial.variable(0), mapOf(0 to value))

        assertEquals(1, sign)
    }

    @Test
    fun `point sign certificates should observe requesting caller cancellation`() {
        val value = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val source = polynomial.add(Polynomial.ONE, Polynomial.variable(0))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { true }))

        assertFailsWith<PolynomialInterrupted> { caller.signAt(source, mapOf(0 to value)) }
    }

    @Test
    fun `point sign certificates should retain captured coordinates after map mutation`() {
        val coordinates = mutableMapOf(0 to arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last())
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))
        val point = caller.pointEvaluation(coordinates)
        val source = polynomial.add(Polynomial.ONE, Polynomial.variable(0))
        coordinates[0] = arithmetic.rational(BigFraction.ofLong(-4))

        val sign = point.signAt(source)

        assertEquals(1, sign)
    }

    @Test
    fun `owned sign certificates should survive producer cancellation`() {
        var cancelled = false
        val producer = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { cancelled }))
        val value = producer.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val source = polynomial.add(Polynomial.ONE, Polynomial.variable(0))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))
        cancelled = true

        val sign = caller.signAt(source, mapOf(0 to value))

        assertEquals(1, sign)
    }

    @Test
    fun `algebraic coefficient signs should avoid unrelated scalar images`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val constant = arithmetic.add(base, arithmetic.rational(BigFraction.ONE))
        val source = arithmetic.polynomial(listOf(constant, arithmetic.rational(BigFraction.ONE)))
        val value = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))

        val sign = caller.signAt(source, value)

        assertEquals(1, sign)
    }

    @Test
    fun `algebraic coefficient signs should retain exact zero on a selected root`() {
        val value = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val source = arithmetic.polynomial(listOf(arithmetic.negate(value), arithmetic.rational(BigFraction.ONE)))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))

        val sign = caller.signAt(source, value)

        assertEquals(0, sign)
    }

    @Test
    fun `separated univariate signs should retain the caller without wide base division`() {
        val defining = RationalPolynomial(List(13) { index ->
            when (index) {
                0 -> BigFraction.ofLong(-2)
                12 -> BigFraction.ONE
                else -> BigFraction.ZERO
            }
        })
        val value = arithmetic.roots(defining).last()
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxDenseCoefficients = 2)))

        val sign = caller.signAt(RationalPolynomial.of(1, 1), value)

        assertEquals(1, sign)
    }

    @Test
    fun `linear shared lifting should retain the root relation for later arithmetic`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val x = Polynomial.variable(0)
        val relation = polynomial.subtract(Polynomial.variable(1),
            polynomial.subtract(polynomial.power(x, 2), Polynomial.ONE))
        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to base))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 25)))

        val root = caller.roots(specialized).single()
        val sum = caller.add(root, base)

        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-11, -3, 3, 1), sum))
    }

    @Test
    fun `linear shared lifting should retain an admitted joint resultant after image refusal`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val relation = polynomial.subtract(polynomial.multiply(Polynomial.variable(0), Polynomial.variable(1)),
            Polynomial.ONE)
        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to base))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 16)))

        val root = caller.roots(specialized).single()

        assertEquals(0, arithmetic.compare(arithmetic.multiply(base, root), arithmetic.rational(BigFraction.ONE)))
    }

    @Test
    fun `related coordinates should share a single coefficient elimination field`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val square = arithmetic.power(base, 2)
        val z = Polynomial.variable(2)
        val relation = polynomial.add(polynomial.add(polynomial.power(z, 2),
            polynomial.multiply(Polynomial.variable(0), z)), Polynomial.variable(1))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 25)))

        val roots = caller.roots(caller.specialize(relation, 2, mapOf(0 to base, 1 to square)))

        assertTrue(roots.isEmpty())
    }

    @Test
    fun `shared coordinate capture should distinguish different indexed conjugates`() {
        val bases = arithmetic.roots(RationalPolynomial.of(-2, 0, 1))
        val relation = polynomial.subtract(Polynomial.variable(2),
            polynomial.add(Polynomial.variable(0), Polynomial.variable(1)))

        val roots = arithmetic.roots(arithmetic.specialize(relation, 2,
            mapOf(0 to bases.first(), 1 to bases.last())))

        assertEquals(1, roots.size)
        assertEquals(0, arithmetic.sign(roots.single()))
    }

    @Test
    fun `captured related coordinates should use the lifting caller after producer cancellation`() {
        var cancelled = false
        val producer = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { cancelled }))
        val base = producer.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val square = producer.power(base, 2)
        val z = Polynomial.variable(2)
        val relation = polynomial.add(polynomial.add(polynomial.power(z, 2),
            polynomial.multiply(Polynomial.variable(0), z)), Polynomial.variable(1))
        val specialized = producer.specialize(relation, 2, mapOf(0 to base, 1 to square))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 25)))
        cancelled = true

        val roots = caller.roots(specialized)

        assertTrue(roots.isEmpty())
    }

    @Test
    fun `shared coordinate capture should own its expressions after point mutation`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val coordinates = mutableMapOf(0 to base, 1 to arithmetic.power(base, 2))
        val z = Polynomial.variable(2)
        val relation = polynomial.add(polynomial.add(polynomial.power(z, 2),
            polynomial.multiply(Polynomial.variable(0), z)), Polynomial.variable(1))
        val specialized = arithmetic.specialize(relation, 2, coordinates)
        coordinates[0] = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        coordinates[1] = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()

        val roots = arithmetic.roots(specialized)

        assertTrue(roots.isEmpty())
    }

    @Test
    fun `shared images should add within a single image elimination matrix`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val square = arithmetic.evaluate(RationalPolynomial.of(0, 0, 1), base)
        val limited = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 25)))

        val sum = limited.add(arithmetic.refine(base), square)

        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-6, -6, 0, 1), sum))
        assertEquals(1, arithmetic.sign(sum))
    }

    @Test
    fun `shared affine images should multiply within a single image elimination matrix`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val left = arithmetic.evaluate(RationalPolynomial.of(1, 1), base)
        val right = arithmetic.evaluate(RationalPolynomial.of(-1, 0, 1), base)
        val limited = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 25)))

        val product = limited.multiply(left, right)

        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-9, 9, -3, 1), product))
    }

    @Test
    fun `refined nested images should retain their common primitive root`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val square = arithmetic.evaluate(RationalPolynomial.of(0, 0, 1), base)
        val fourth = arithmetic.power(arithmetic.refine(square), 2)
        val limited = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 25)))

        val sum = limited.add(fourth, square)

        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-20, -12, 0, 1), sum))
    }

    @Test
    fun `images of distinct conjugates should preserve their separate root choices`() {
        val bases = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 0, 1))
        val left = arithmetic.evaluate(RationalPolynomial.of(1, 1), bases.last())
        val right = arithmetic.evaluate(RationalPolynomial.of(-1, 1), bases.first())

        val sum = arithmetic.add(left, right)

        assertEquals(0, arithmetic.sign(sum))
    }

    @Test
    fun `shared images should use the requesting caller after producer cancellation`() {
        var cancelled = false
        val producer = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { cancelled }))
        val base = producer.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val square = producer.evaluate(RationalPolynomial.of(0, 0, 1), base)
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 25)))
        cancelled = true

        val sum = caller.add(base, square)

        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-6, -6, 0, 1), sum))
    }

    @Test
    fun `interrupted image arithmetic should leave owned values usable by another caller`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val square = arithmetic.evaluate(RationalPolynomial.of(0, 0, 1), base)
        val interrupted = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { true }))
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 25)))

        assertFailsWith<PolynomialInterrupted> { interrupted.add(base, square) }
        val sum = caller.add(base, square)

        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-6, -6, 0, 1), sum))
    }

    @Test
    fun `wide captured bases should leave admitted scalar arithmetic available`() {
        val defining = RationalPolynomial(List(13) { index ->
            when (index) {
                0 -> BigFraction.ofLong(-2)
                12 -> BigFraction.ONE
                else -> BigFraction.ZERO
            }
        })
        val base = arithmetic.roots(defining).last()
        val first = arithmetic.evaluate(RationalPolynomial(List(5) { index ->
            if (index == 4) BigFraction.ONE else BigFraction.ZERO
        }), base)
        val second = arithmetic.evaluate(RationalPolynomial(List(9) { index ->
            if (index == 8) BigFraction.ONE else BigFraction.ZERO
        }), base)
        val caller = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(
            maxDenseCoefficients = 10, maxMatrixCells = 36,
        )))

        val sum = caller.add(first, second)

        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-6, -6, 0, 1), sum))
    }

    @Test
    fun `shared specialization should not require coefficient image elimination`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val relation = polynomial.subtract(Polynomial.variable(1), polynomial.power(Polynomial.variable(0), 2))
        val limited = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))

        val specialized = limited.specialize(relation, 1, mapOf(0 to base))

        assertEquals(1, specialized.degree)
    }

    @Test
    fun `captured coefficient images should use the evaluating caller`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 0, 1)).single()
        val value = arithmetic.roots(RationalPolynomial.of(-4, 0, 0, 1)).single()
        val relation = polynomial.subtract(Polynomial.variable(1), polynomial.power(Polynomial.variable(0), 2))
        var cancelled = false
        val producer = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { cancelled },
            limits = PolynomialLimits(maxMatrixCells = 0)))
        val specialized = producer.specialize(relation, 1, mapOf(0 to base))
        cancelled = true

        val result = arithmetic.evaluate(specialized, value)

        assertEquals(0, arithmetic.sign(result))
    }

    @Test
    fun `captured coefficient widths should obey the lifting caller dense limit`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val relation = polynomial.subtract(Polynomial.variable(1), polynomial.power(Polynomial.variable(0), 3))
        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to base))
        val limited = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxDenseCoefficients = 3)))

        val failure = assertFailsWith<PolynomialResourceLimit> { limited.roots(specialized) }

        assertEquals(PolynomialResource.DENSE_COEFFICIENTS, failure.resource)
    }

    @Test
    fun `rational shared coefficients should lift without elimination matrices`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val relation = polynomial.subtract(polynomial.power(Polynomial.variable(1), 2),
            polynomial.power(Polynomial.variable(0), 2))
        val limited = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))
        val specialized = limited.specialize(relation, 1, mapOf(0 to base))

        val roots = limited.roots(specialized)

        assertEquals(2, roots.size)
        assertEquals(0, arithmetic.compare(roots[0], arithmetic.negate(base)))
        assertEquals(0, arithmetic.compare(roots[1], base))
    }

    @Test
    fun `shared coefficient conjugates should retain only the selected quadratic roots`() {
        val bases = arithmetic.roots(RationalPolynomial.of(-2, 0, 1))
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val relation = polynomial.add(polynomial.add(polynomial.power(y, 2), polynomial.multiply(x, y)), x)

        val families = bases.map { arithmetic.roots(arithmetic.specialize(relation, 1, mapOf(0 to it))) }

        assertEquals(listOf(2, 0), families.map { it.size })
        for (root in families.first()) {
            assertEquals(0, arithmetic.sign(arithmetic.evaluate(relation, mapOf(0 to bases.first(), 1 to root))))
        }
    }

    @Test
    fun `shared coefficient lifting should retain rational and repeated irrational roots once`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val y = Polynomial.variable(1)
        val rationalFactor = polynomial.subtract(y, Polynomial.ONE)
        val irrationalFactor = polynomial.subtract(y, Polynomial.variable(0))
        val relation = polynomial.multiply(polynomial.power(rationalFactor, 2), polynomial.power(irrationalFactor, 2))

        val roots = arithmetic.roots(arithmetic.specialize(relation, 1, mapOf(0 to base)))

        assertEquals(2, roots.size)
        assertEquals(0, arithmetic.compare(roots[0], arithmetic.rational(BigFraction.ONE)))
        assertEquals(0, arithmetic.compare(roots[1], base))
    }

    @Test
    fun `shared coefficient lifting should exclude null unselected conjugates`() {
        val base = arithmetic.roots(RationalPolynomial.of(0, -2, 0, 1)).last()
        val relation = polynomial.multiply(Polynomial.variable(0),
            polynomial.subtract(polynomial.power(Polynomial.variable(1), 2), Polynomial.ONE))

        val roots = arithmetic.roots(arithmetic.specialize(relation, 1, mapOf(0 to base)))

        assertEquals(2, roots.size)
        assertEquals(0, arithmetic.compare(roots[0], arithmetic.rational(BigFraction.MINUS_ONE)))
        assertEquals(0, arithmetic.compare(roots[1], arithmetic.rational(BigFraction.ONE)))
    }

    @Test
    fun `shared coefficient division should exclude factors where its divisor vanishes`() {
        val bases = arithmetic.roots(RationalPolynomial.of(6, 0, -5, 0, 1))
        val base = bases[2]
        val x = Polynomial.variable(0)
        val leading = polynomial.subtract(polynomial.power(x, 2), Polynomial.constant(BigFraction.ofLong(3)))
        val relation = polynomial.subtract(polynomial.multiply(leading, Polynomial.variable(1)), x)

        val roots = arithmetic.roots(arithmetic.specialize(relation, 1, mapOf(0 to base)))

        assertEquals(1, roots.size)
        assertEquals(0, arithmetic.compare(roots.single(), arithmetic.negate(base)))
    }

    @Test
    fun `shared coefficient zero tests should retain the selected defining factor`() {
        val base = arithmetic.roots(RationalPolynomial.of(6, 0, -5, 0, 1))[2]
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val zero = polynomial.subtract(polynomial.power(x, 2), Polynomial.constant(BigFraction.ofLong(2)))
        val relation = polynomial.subtract(polynomial.add(polynomial.power(y, 2), polynomial.multiply(zero, y)), x)

        val roots = arithmetic.roots(arithmetic.specialize(relation, 1, mapOf(0 to base)))

        assertEquals(2, roots.size)
        for (root in roots) assertEquals(0, arithmetic.compare(arithmetic.power(root, 2), base))
    }

    @Test
    fun `shared specialization should capture rational substitutions and its original base`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val x = Polynomial.variable(0)
        val relation = polynomial.subtract(Polynomial.variable(2), polynomial.multiply(x, Polynomial.variable(1)))
        val coordinates = mutableMapOf(0 to base, 1 to arithmetic.rational(BigFraction.ofLong(3)))
        val specialized = arithmetic.specialize(relation, 2, coordinates)
        coordinates[0] = arithmetic.roots(RationalPolynomial.of(-3, 0, 1)).last()
        coordinates[1] = arithmetic.rational(BigFraction.ofLong(4))

        val roots = arithmetic.roots(specialized)

        assertEquals(1, roots.size)
        assertEquals(0, arithmetic.compare(roots.single(), arithmetic.evaluate(RationalPolynomial.of(0, 3), base)))
    }

    @Test
    fun `captured shared specialization should obey the lifting caller matrix limit`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val relation = polynomial.subtract(polynomial.power(Polynomial.variable(1), 2), Polynomial.variable(0))
        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to base))
        val limited = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0)))

        val failure = assertFailsWith<PolynomialResourceLimit> { limited.roots(specialized) }

        assertEquals(PolynomialResource.MATRIX_CELLS, failure.resource)
    }

    @Test
    fun `captured shared specialization should obey the lifting caller dense limit`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val relation = polynomial.subtract(polynomial.power(Polynomial.variable(1), 2), Polynomial.variable(0))
        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to base))
        val limited = AlgebraicArithmetic(PolynomialArithmetic(limits = PolynomialLimits(maxDenseCoefficients = 2)))

        val failure = assertFailsWith<PolynomialResourceLimit> { limited.roots(specialized) }

        assertEquals(PolynomialResource.DENSE_COEFFICIENTS, failure.resource)
    }

    @Test
    fun `completed shared lifting should still obey caller cancellation`() {
        val base = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val relation = polynomial.subtract(polynomial.power(Polynomial.variable(1), 2), Polynomial.variable(0))
        val specialized = arithmetic.specialize(relation, 1, mapOf(0 to base))
        var cancelled = false
        val limited = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { cancelled }))
        limited.roots(specialized)
        cancelled = true

        assertFailsWith<PolynomialInterrupted> { limited.roots(specialized) }
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
