package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction

internal class AlgebraicArithmetic(val arithmetic: PolynomialArithmetic = PolynomialArithmetic()) {
    private val univariate = RationalPolynomialArithmetic(arithmetic)

    fun rational(value: BigFraction): AlgebraicReal {
        arithmetic.step()
        return AlgebraicReal(
            RationalPolynomial(listOf(value.negated(), BigFraction.ONE)),
            1,
            RationalInterval(value, value),
        )
    }

    fun roots(polynomial: RationalPolynomial): List<AlgebraicReal> {
        val defining = univariate.squareFree(polynomial)
        return univariate.isolate(defining).mapIndexed { index, interval ->
            AlgebraicReal(defining, index + 1, interval)
        }
    }

    fun refine(value: AlgebraicReal): AlgebraicReal = AlgebraicReal(
        value.definingPolynomial, value.rootIndex, univariate.refine(value.definingPolynomial, value.interval),
    )

    fun compare(left: AlgebraicReal, right: AlgebraicReal): Int {
        arithmetic.step()
        if (left === right) return 0
        if (left.definingPolynomial == right.definingPolynomial && left.rootIndex == right.rootIndex) return 0
        left.rational?.let { return -compareRational(right, it) }
        right.rational?.let { return compareRational(left, it) }
        val common = univariate.gcd(left.definingPolynomial, right.definingPolynomial)
        val sequence = if (common.degree > 0) univariate.sturm(common) else emptyList()
        var a = left
        var b = right
        while (true) {
            arithmetic.step()
            if (a.interval.upper <= b.interval.lower) return -1
            if (b.interval.upper <= a.interval.lower) return 1
            val overlap = RationalInterval(
                if (a.interval.lower >= b.interval.lower) a.interval.lower else b.interval.lower,
                if (a.interval.upper <= b.interval.upper) a.interval.upper else b.interval.upper,
            )
            if (sequence.isNotEmpty() && univariate.countOpen(sequence, overlap) > 0) return 0
            a = refine(a)
            b = refine(b)
            a.rational?.let { return -compareRational(b, it) }
            b.rational?.let { return compareRational(a, it) }
        }
    }

    private fun compareRational(value: AlgebraicReal, other: BigFraction): Int {
        var current = value
        while (true) {
            arithmetic.step()
            current.rational?.let { return it.compareTo(other) }
            if (current.interval.upper <= other) return -1
            if (current.interval.lower >= other) return 1
            if (univariate.evaluate(current.definingPolynomial, other).isZero) return 0
            current = refine(current)
        }
    }

    fun sign(value: AlgebraicReal): Int = compareRational(value, BigFraction.ZERO)

    fun sampleBetween(lower: AlgebraicReal?, upper: AlgebraicReal?): BigFraction {
        arithmetic.step()
        if (lower == null && upper == null) return BigFraction.ZERO
        if (lower == null) return checkNotNull(upper).lowerBound - BigFraction.ONE
        if (upper == null) return lower.upperBound + BigFraction.ONE
        require(compare(lower, upper) < 0) { "sector boundaries must be strictly ordered" }
        var a = lower
        var b = upper
        while (a.upperBound >= b.lowerBound) {
            a = refine(a)
            b = refine(b)
        }
        return (a.upperBound + b.lowerBound) * BigFraction.ofLong(2).reciprocal()
    }

    fun signAt(polynomial: RationalPolynomial, value: AlgebraicReal): Int {
        arithmetic.step()
        value.rational?.let { return univariate.evaluate(polynomial, it).signum() }
        val remainder = univariate.divide(polynomial, value.definingPolynomial).second
        if (remainder.degree <= 0) return remainder[0].signum()
        val common = univariate.gcd(remainder, value.definingPolynomial)
        if (common.degree > 0 && univariate.countOpen(univariate.sturm(common), value.interval) > 0) return 0
        var current = value
        while (true) {
            arithmetic.step()
            val enclosure = enclose(remainder, current.interval)
            if (enclosure.lower.signum() > 0) return 1
            if (enclosure.upper.signum() < 0) return -1
            current = refine(current)
            current.rational?.let { return univariate.evaluate(remainder, it).signum() }
        }
    }

    fun evaluate(polynomial: RationalPolynomial, value: AlgebraicReal): AlgebraicReal {
        arithmetic.step()
        value.rational?.let { return rational(univariate.evaluate(polynomial, it)) }
        val remainder = univariate.divide(polynomial, value.definingPolynomial).second
        if (remainder.degree <= 0) return rational(remainder[0])
        if (remainder == RationalPolynomial.X) return value
        if (remainder.degree == 1) return affine(value, remainder[1], remainder[0])
        val y = lift(value.definingPolynomial, 0)
        val relation = arithmetic.subtract(Polynomial.variable(1), lift(remainder, 0))
        val annihilator = extract(arithmetic.resultant(y, relation, 0), 1)
        var current = value
        return select(annihilator) {
            val enclosure = enclose(remainder, current.interval)
            current = refine(current)
            enclosure
        }
    }

    fun negate(value: AlgebraicReal): AlgebraicReal = affine(value, BigFraction.MINUS_ONE, BigFraction.ZERO)

    private fun affine(value: AlgebraicReal, scale: BigFraction, offset: BigFraction): AlgebraicReal {
        arithmetic.step()
        value.rational?.let { return rational(it * scale + offset) }
        if (scale.isZero) return rational(offset)
        val inverse = scale.reciprocal()
        val replacement = RationalPolynomial(listOf(offset.negated() * inverse, inverse))
        var defining = RationalPolynomial.ZERO
        for (coefficient in value.definingPolynomial.coefficients.asReversed()) {
            defining = univariate.add(
                univariate.multiply(defining, replacement), RationalPolynomial(listOf(coefficient)),
            )
        }
        val a = value.lowerBound * scale + offset
        val b = value.upperBound * scale + offset
        val interval = if (scale.signum() > 0) RationalInterval(a, b) else RationalInterval(b, a)
        val index = if (scale.signum() > 0) value.rootIndex else {
            univariate.isolate(value.definingPolynomial).size + 1 - value.rootIndex
        }
        return AlgebraicReal(univariate.monic(defining), index, interval)
    }

    fun add(left: AlgebraicReal, right: AlgebraicReal): AlgebraicReal {
        arithmetic.step()
        left.rational?.let { return evaluate(RationalPolynomial(listOf(it, BigFraction.ONE)), right) }
        right.rational?.let { return evaluate(RationalPolynomial(listOf(it, BigFraction.ONE)), left) }
        if (compare(left, right) == 0) return evaluate(RationalPolynomial.of(0, 2), left)
        if (compare(left, negate(right)) == 0) return rational(BigFraction.ZERO)
        val first = lift(left.definingPolynomial, 0)
        val replacement = arithmetic.subtract(Polynomial.variable(1), Polynomial.variable(0))
        val second = arithmetic.substitute(lift(right.definingPolynomial, 0), 0, replacement)
        val annihilator = extract(arithmetic.resultant(first, second, 0), 1)
        var a = left
        var b = right
        return select(annihilator) {
            val enclosure = intervalAdd(a.interval, b.interval)
            a = refine(a)
            b = refine(b)
            enclosure
        }
    }

    fun subtract(left: AlgebraicReal, right: AlgebraicReal): AlgebraicReal {
        arithmetic.step()
        if (compare(left, right) == 0) return rational(BigFraction.ZERO)
        return add(left, negate(right))
    }

    fun multiply(left: AlgebraicReal, right: AlgebraicReal): AlgebraicReal {
        arithmetic.step()
        left.rational?.let { return evaluate(RationalPolynomial(listOf(BigFraction.ZERO, it)), right) }
        right.rational?.let { return evaluate(RationalPolynomial(listOf(BigFraction.ZERO, it)), left) }
        if (compare(left, right) == 0) return evaluate(RationalPolynomial.of(0, 0, 1), left)
        val first = lift(left.definingPolynomial, 0)
        val degree = right.definingPolynomial.degree
        val second = Polynomial.of(right.definingPolynomial.coefficients.mapIndexed { index, coefficient ->
            PolynomialTerm(Monomial.of(mapOf(0 to degree - index, 1 to index)), coefficient)
        })
        val annihilator = extract(arithmetic.resultant(first, second, 0), 1)
        var a = left
        var b = right
        return select(annihilator) {
            val enclosure = intervalMultiply(a.interval, b.interval)
            a = refine(a)
            b = refine(b)
            enclosure
        }
    }

    fun reciprocal(value: AlgebraicReal): AlgebraicReal {
        require(sign(value) != 0) { "reciprocal of zero" }
        value.rational?.let { return rational(it.reciprocal()) }
        val annihilator = RationalPolynomial(value.definingPolynomial.coefficients.asReversed())
        var current = value
        return select(annihilator) {
            while (current.interval.lower.signum() <= 0 && current.interval.upper.signum() >= 0) {
                current = refine(current)
            }
            val enclosure = RationalInterval(current.interval.upper.reciprocal(), current.interval.lower.reciprocal())
            current = refine(current)
            enclosure
        }
    }

    fun power(value: AlgebraicReal, exponent: Int): AlgebraicReal {
        require(exponent >= 0)
        var result = rational(BigFraction.ONE)
        var base = value
        var remaining = exponent
        while (remaining != 0) {
            arithmetic.step()
            if (remaining and 1 != 0) result = multiply(result, base)
            remaining = remaining ushr 1
            if (remaining != 0) base = multiply(base, base)
        }
        return result
    }

    fun evaluate(polynomial: Polynomial, coordinates: Map<Int, AlgebraicReal>): AlgebraicReal {
        var result = rational(BigFraction.ZERO)
        for ((monomial, coefficient) in polynomial.terms) {
            arithmetic.step()
            var term = rational(coefficient)
            for (variable in monomial.variables) {
                val coordinate = requireNotNull(coordinates[variable]) { "missing algebraic coordinate" }
                term = multiply(term, power(coordinate, monomial.exponent(variable)))
            }
            result = add(result, term)
        }
        return result
    }

    fun specialize(polynomial: Polynomial, variable: Int, coordinates: Map<Int, AlgebraicReal>): AlgebraicPolynomial =
        polynomial(arithmetic.coefficients(polynomial, variable).map { evaluate(it, coordinates) })

    fun polynomial(coefficients: List<AlgebraicReal>): AlgebraicPolynomial {
        arithmetic.step()
        var size = coefficients.size
        while (size > 0 && sign(coefficients[size - 1]) == 0) size--
        return AlgebraicPolynomial(coefficients.take(size))
    }

    fun evaluate(polynomial: AlgebraicPolynomial, value: AlgebraicReal): AlgebraicReal {
        var result = rational(BigFraction.ZERO)
        for (coefficient in polynomial.coefficients.asReversed()) result = add(multiply(result, value), coefficient)
        return result
    }

    fun roots(polynomial: AlgebraicPolynomial): List<AlgebraicReal> {
        arithmetic.step()
        require(!polynomial.isZero) { "zero polynomial has no finite root list" }
        if (polynomial.degree == 0) return emptyList()
        // Independent coefficient conjugates give a superset of roots. The leading coefficient's
        // annihilator excludes zero, ensuring every conjugate polynomial retains positive degree.
        val coefficients = polynomial.coefficients
        val variable = coefficients.size
        var relation = Polynomial.ZERO
        for ((index, coefficient) in coefficients.withIndex()) {
            arithmetic.step()
            val scalar = coefficient.rational?.let(Polynomial::constant) ?: Polynomial.variable(index)
            val power = arithmetic.power(Polynomial.variable(variable), index)
            relation = arithmetic.add(relation, arithmetic.multiply(scalar, power))
        }
        for ((index, coefficient) in coefficients.withIndex()) {
            if (coefficient.rational != null) continue
            var defining = coefficient.definingPolynomial
            if (index == polynomial.degree && defining[0].isZero) {
                defining = univariate.divideExact(defining, RationalPolynomial.X)
            }
            relation = arithmetic.resultant(lift(defining, index), relation, index)
        }
        val annihilator = extract(relation, variable)
        return roots(annihilator).filter { sign(evaluate(polynomial, it)) == 0 }
    }

    private fun select(polynomial: RationalPolynomial, enclosure: () -> RationalInterval): AlgebraicReal {
        var candidates = roots(polynomial)
        check(candidates.isNotEmpty()) { "annihilator has no real root" }
        while (true) {
            arithmetic.step()
            val interval = enclosure()
            candidates = candidates.filter {
                it.interval.lower <= interval.upper && interval.lower <= it.interval.upper
            }
            check(candidates.isNotEmpty()) { "root enclosure lost its annihilator" }
            if (candidates.size == 1) return candidates.single()
            candidates = candidates.map(::refine)
        }
    }

    private fun lift(value: RationalPolynomial, variable: Int): Polynomial = Polynomial.of(
        value.coefficients.mapIndexed { degree, coefficient ->
            arithmetic.step()
            PolynomialTerm(Monomial.of(mapOf(variable to degree)), coefficient)
        },
    )

    private fun extract(value: Polynomial, variable: Int): RationalPolynomial {
        require(value.variables.all { it == variable })
        return RationalPolynomial(arithmetic.coefficients(value, variable).map { it.coefficient(Monomial.ONE) })
    }

    private fun enclose(polynomial: RationalPolynomial, interval: RationalInterval): RationalInterval {
        var result = RationalInterval(BigFraction.ZERO, BigFraction.ZERO)
        for (coefficient in polynomial.coefficients.asReversed()) {
            arithmetic.step()
            result = intervalAdd(intervalMultiply(result, interval), RationalInterval(coefficient, coefficient))
        }
        return result
    }

    private fun intervalAdd(left: RationalInterval, right: RationalInterval): RationalInterval =
        RationalInterval(left.lower + right.lower, left.upper + right.upper)

    private fun intervalMultiply(left: RationalInterval, right: RationalInterval): RationalInterval {
        arithmetic.step()
        val products = listOf(
            left.lower * right.lower, left.lower * right.upper, left.upper * right.lower, left.upper * right.upper,
        )
        return RationalInterval(
            products.reduce { a, b -> if (a <= b) a else b },
            products.reduce { a, b -> if (a >= b) a else b },
        )
    }
}
