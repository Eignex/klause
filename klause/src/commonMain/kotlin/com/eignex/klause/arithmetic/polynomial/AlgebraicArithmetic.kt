package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction

/**
 * Exact arithmetic on real algebraic numbers held as a defining polynomial plus an isolating interval.
 * Sums, products and polynomial images take their defining polynomial from a resultant and pick the
 * right root by refining enclosures (R. Loos, "Computing in algebraic extensions", Computer Algebra,
 * Computing Supplementum 4, 1982, https://doi.org/10.1007/978-3-7091-3406-1_12).
 */
internal class AlgebraicArithmetic(val arithmetic: PolynomialArithmetic = PolynomialArithmetic()) {
    private val univariate = RationalPolynomialArithmetic(arithmetic)
    private data class RootCount(val polynomial: RationalPolynomial, val count: Int)
    private var currentRootCount: RootCount? = null
    private var previousRootCount: RootCount? = null

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
        val intervals = univariate.isolate(defining)
        if (intervals.any { arithmetic.step(); !it.isPoint }) rememberRootCount(defining, intervals.size)
        return intervals.mapIndexed { index, interval ->
            AlgebraicReal(defining, index + 1, interval)
        }
    }

    private fun knownRootCount(polynomial: RationalPolynomial): Int? {
        arithmetic.step()
        for (entry in arrayOf(currentRootCount, previousRootCount)) {
            arithmetic.step()
            if (entry == null) continue
            if (entry.polynomial === polynomial) return entry.count
            if (entry.polynomial.degree != polynomial.degree) continue
            var same = true
            for (index in 0..polynomial.degree) {
                arithmetic.step()
                if (entry.polynomial[index] != polynomial[index]) {
                    same = false
                    break
                }
            }
            if (same) return entry.count
        }
        return null
    }

    private fun rememberRootCount(polynomial: RationalPolynomial, count: Int) {
        if (knownRootCount(polynomial) != null) return
        arithmetic.step()
        val completed = RootCount(polynomial, count)
        previousRootCount = currentRootCount
        currentRootCount = completed
    }

    private fun realRootCount(polynomial: RationalPolynomial): Int = knownRootCount(polynomial)
        ?: univariate.isolate(polynomial).size.also { rememberRootCount(polynomial, it) }

    fun refine(value: AlgebraicReal): AlgebraicReal = AlgebraicReal(
        value.definingPolynomial, value.rootIndex, univariate.refine(value.definingPolynomial, value.interval),
        value.image,
    )

    fun compare(left: AlgebraicReal, right: AlgebraicReal): Int {
        arithmetic.step()
        if (left === right) return 0
        if (left.definingPolynomial == right.definingPolynomial && left.rootIndex == right.rootIndex) return 0
        left.rational?.let { return -compareRational(right, it) }
        right.rational?.let { return compareRational(left, it) }
        sharedSign(left, right)?.let { return it }
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
        value.image?.let { image ->
            try {
                arithmetic.denseSize(image.base.definingPolynomial.degree)
                val difference = univariate.add(image.polynomial, RationalPolynomial(listOf(other.negated())))
                return signAt(difference, image.base)
            } catch (_: PolynomialResourceLimit) {
                // Image provenance is optional; the scalar's own representation remains available.
            }
        }
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
        var a: AlgebraicReal = lower
        var b: AlgebraicReal = upper
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
        value.image?.let { image ->
            try {
                return evaluate(composeImage(remainder, image), image.base)
            } catch (_: PolynomialResourceLimit) {
                // The captured base can be wider than the scalar admitted by this caller.
            }
        }
        val y = lift(value.definingPolynomial, 0)
        val relation = arithmetic.subtract(Polynomial.variable(1), lift(remainder, 0))
        val annihilator = extract(arithmetic.resultant(y, relation, 0), 1)
        var current = value
        val result = select(annihilator) {
            val enclosure = enclose(remainder, current.interval)
            current = refine(current)
            enclosure
        }
        return captureImage(result, value, remainder)
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
            realRootCount(value.definingPolynomial) - value.rootIndex + 1
        }
        val result = AlgebraicReal(univariate.monic(defining), index, interval)
        return captureImage(result, value, RationalPolynomial(listOf(offset, scale)))
    }

    fun add(left: AlgebraicReal, right: AlgebraicReal): AlgebraicReal {
        arithmetic.step()
        left.rational?.let { return evaluate(RationalPolynomial(listOf(it, BigFraction.ONE)), right) }
        right.rational?.let { return evaluate(RationalPolynomial(listOf(it, BigFraction.ONE)), left) }
        sharedImage(left, right, product = false)?.let { return it }
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
        sharedImage(left, right, product = false, subtract = true)?.let { return it }
        if (compare(left, right) == 0) return rational(BigFraction.ZERO)
        return add(left, negate(right))
    }

    fun multiply(left: AlgebraicReal, right: AlgebraicReal): AlgebraicReal {
        arithmetic.step()
        left.rational?.let { return evaluate(RationalPolynomial(listOf(BigFraction.ZERO, it)), right) }
        right.rational?.let { return evaluate(RationalPolynomial(listOf(BigFraction.ZERO, it)), left) }
        sharedImage(left, right, product = true)?.let { return it }
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

    private data class CommonImages(
        val base: AlgebraicReal,
        val left: RationalPolynomial,
        val right: RationalPolynomial,
    )

    private fun commonImages(left: AlgebraicReal, right: AlgebraicReal): CommonImages? {
        if (left.image == null && right.image == null) return null
        if (left.rational != null || right.rational != null) return null
        arithmetic.step()
        val a = left.image?.base ?: left
        val b = right.image?.base ?: right
        if (!sameIndexedRoot(a, b)) return null
        return CommonImages(a, left.image?.polynomial ?: RationalPolynomial.X,
            right.image?.polynomial ?: RationalPolynomial.X)
    }

    private fun sameIndexedRoot(a: AlgebraicReal, b: AlgebraicReal): Boolean {
        arithmetic.step()
        if (a.rootIndex != b.rootIndex) return false
        arithmetic.denseSize(a.definingPolynomial.degree)
        arithmetic.denseSize(b.definingPolynomial.degree)
        if (a.definingPolynomial.degree != b.definingPolynomial.degree) return false
        for (index in 0..a.definingPolynomial.degree) {
            arithmetic.step()
            if (a.definingPolynomial[index] != b.definingPolynomial[index]) return false
        }
        return true
    }

    private fun sharedImage(
        left: AlgebraicReal,
        right: AlgebraicReal,
        product: Boolean,
        subtract: Boolean = false,
    ): AlgebraicReal? = try {
        commonImages(left, right)?.let { image ->
            val polynomial = if (product) univariate.multiply(image.left, image.right)
            else univariate.add(image.left, if (subtract) {
                univariate.scale(image.right, BigFraction.MINUS_ONE)
            } else image.right)
            evaluate(polynomial, image.base)
        }
    } catch (_: PolynomialResourceLimit) {
        null
    }

    private fun sharedSign(left: AlgebraicReal, right: AlgebraicReal): Int? = try {
        commonImages(left, right)?.let { image ->
            signAt(univariate.add(image.left, univariate.scale(image.right, BigFraction.MINUS_ONE)), image.base)
        }
    } catch (_: PolynomialResourceLimit) {
        null
    }

    private fun composeImage(polynomial: RationalPolynomial, image: AlgebraicImage): RationalPolynomial {
        arithmetic.denseSize(image.base.definingPolynomial.degree)
        var result = RationalPolynomial.ZERO
        for (coefficient in polynomial.coefficients.asReversed()) {
            arithmetic.step()
            result = univariate.divide(univariate.add(univariate.multiply(result, image.polynomial),
                RationalPolynomial(listOf(coefficient))), image.base.definingPolynomial).second
        }
        return result
    }

    private fun captureImage(
        result: AlgebraicReal,
        value: AlgebraicReal,
        polynomial: RationalPolynomial,
    ): AlgebraicReal {
        if (result.rational != null) return result
        arithmetic.step()
        val previous = value.image
        val image = if (previous == null) AlgebraicImage(value, polynomial) else {
            try {
                AlgebraicImage(previous.base, composeImage(polynomial, previous))
            } catch (_: PolynomialResourceLimit) {
                return result
            }
        }
        return AlgebraicReal(result.definingPolynomial, result.rootIndex, result.interval, image)
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

    fun evaluate(polynomial: Polynomial, coordinates: Map<Int, AlgebraicReal>): AlgebraicReal =
        evaluateAt(polynomial) { variable, exponent ->
            val coordinate = requireNotNull(coordinates[variable]) { "missing algebraic coordinate" }
            power(coordinate, exponent)
        }

    fun pointEvaluation(coordinates: Map<Int, AlgebraicReal>): PointEvaluation = PointEvaluation(coordinates)

    inner class PointEvaluation internal constructor(coordinates: Map<Int, AlgebraicReal>) {
        private val point = buildMap {
            arithmetic.step()
            for ((id, value) in coordinates) {
                arithmetic.step()
                put(id, value)
            }
        }
        private val powers = HashMap<Pair<Int, Int>, AlgebraicReal>()
        private var constantPoint: AffineLineEvaluation.Point? = null
        private var classified = false
        val coordinateCount: Int get() = point.size

        fun coordinate(variable: Int): AlgebraicReal = point.getValue(variable)

        fun evaluate(polynomial: Polynomial): AlgebraicReal =
            capturedConstantPoint()?.evaluate(polynomial) ?: evaluateAt(polynomial, ::power)

        private fun capturedConstantPoint(): AffineLineEvaluation.Point? {
            arithmetic.step()
            if (classified) return constantPoint
            val base = ArrayList<BigFraction>()
            for (id in 0 until point.size) {
                arithmetic.step()
                val value = point[id]?.rational
                if (value == null) {
                    arithmetic.step()
                    classified = true
                    return null
                }
                base.add(value)
            }
            val direction = List(base.size) { arithmetic.step(); BigFraction.ZERO }
            val completed = AffineLineEvaluation(base, direction, this@AlgebraicArithmetic)
                .at(rational(BigFraction.ZERO))
            arithmetic.step()
            constantPoint = completed
            classified = true
            return completed
        }

        private fun power(variable: Int, exponent: Int): AlgebraicReal {
            arithmetic.step()
            val key = variable to exponent
            powers[key]?.let { return it }
            val value = this@AlgebraicArithmetic.power(coordinate(variable), exponent)
            arithmetic.step()
            powers[key] = value
            return value
        }
    }

    private inline fun evaluateAt(
        polynomial: Polynomial,
        power: (Int, Int) -> AlgebraicReal,
    ): AlgebraicReal {
        var result = rational(BigFraction.ZERO)
        for ((monomial, coefficient) in polynomial.terms) {
            arithmetic.step()
            var term = rational(coefficient)
            for (variable in monomial.variables) {
                term = multiply(term, power(variable, monomial.exponent(variable)))
            }
            result = add(result, term)
        }
        return result
    }

    fun specialize(polynomial: Polynomial, variable: Int, coordinates: Map<Int, AlgebraicReal>): AlgebraicPolynomial {
        val coefficients = arithmetic.coefficients(polynomial, variable)
        val captured = captureSpecialization(coefficients, coordinates)
            ?: return this.polynomial(coefficients.map { evaluate(it, coordinates) })
        val expressions = captured.coefficients
        var size = expressions.size
        while (size > 0 && signAt(expressions[size - 1], captured.base) == 0) size--
        return AlgebraicPolynomial(AlgebraicSpecialization(captured.base, expressions.take(size)))
    }

    private fun captureSpecialization(
        coefficients: List<Polynomial>,
        coordinates: Map<Int, AlgebraicReal>,
    ): AlgebraicSpecialization? {
        var baseId: Int? = null
        for (coefficient in coefficients) for (id in coefficient.variables) {
            arithmetic.step()
            if (requireNotNull(coordinates[id]).rational != null) continue
            if (baseId != null && baseId != id) return captureSharedSpecialization(coefficients, coordinates)
            baseId = id
        }
        val id = baseId ?: return null
        val base = coordinates.getValue(id)
        val captured = coefficients.map { coefficient ->
            arithmetic.step()
            var remaining = coefficient
            for (other in coefficient.variables) {
                arithmetic.step()
                if (other == id) continue
                remaining = arithmetic.substitute(
                    remaining, other, Polynomial.constant(requireNotNull(coordinates.getValue(other).rational)),
                )
            }
            extract(remaining, id)
        }
        return AlgebraicSpecialization(base, captured)
    }

    private fun captureSharedSpecialization(
        coefficients: List<Polynomial>,
        coordinates: Map<Int, AlgebraicReal>,
    ): AlgebraicSpecialization? {
        return try {
            var base: AlgebraicReal? = null
            for (coefficient in coefficients) for (id in coefficient.variables) {
                arithmetic.step()
                val value = coordinates.getValue(id)
                if (value.rational != null) continue
                val origin = value.image?.base ?: value
                if (base != null && !sameIndexedRoot(base, origin)) return null
                base = origin
            }
            val root = base ?: return null
            arithmetic.denseSize(root.definingPolynomial.degree)
            val captured = coefficients.map { coefficient ->
                var result = RationalPolynomial.ZERO
                for (term in coefficient.terms) {
                    arithmetic.step()
                    var product = RationalPolynomial(listOf(term.coefficient))
                    for (id in term.monomial.variables) {
                        arithmetic.step()
                        val value = coordinates.getValue(id)
                        val expression = value.rational?.let { RationalPolynomial(listOf(it)) }
                            ?: value.image?.polynomial ?: RationalPolynomial.X
                        repeat(term.monomial.exponent(id)) {
                            arithmetic.step()
                            product = univariate.divide(univariate.multiply(product, expression),
                                root.definingPolynomial).second
                        }
                    }
                    result = univariate.add(result, product)
                }
                result
            }
            AlgebraicSpecialization(root, captured)
        } catch (_: PolynomialResourceLimit) {
            // Optional shared images cannot exclude an admitted scalar coefficient representation.
            null
        }
    }

    fun polynomial(coefficients: List<AlgebraicReal>): AlgebraicPolynomial {
        arithmetic.step()
        var size = coefficients.size
        while (size > 0 && sign(coefficients[size - 1]) == 0) size--
        return AlgebraicPolynomial(coefficients.take(size))
    }

    fun evaluate(polynomial: AlgebraicPolynomial, value: AlgebraicReal): AlgebraicReal {
        var result = rational(BigFraction.ZERO)
        for (coefficient in polynomial.coefficients(this).asReversed()) {
            result = add(multiply(result, value), coefficient)
        }
        return result
    }

    fun roots(polynomial: AlgebraicPolynomial): List<AlgebraicReal> {
        arithmetic.step()
        require(!polynomial.isZero) { "zero polynomial has no finite root list" }
        if (polynomial.degree == 0) return emptyList()
        polynomial.specialization?.let { return CorrelatedAlgebraicRoots(this).roots(it) }
        // Independent coefficient conjugates give a superset of roots. The leading coefficient's
        // annihilator excludes zero, ensuring every conjugate polynomial retains positive degree.
        val coefficients = polynomial.coefficients(this)
        val rational = ArrayList<BigFraction>()
        for (coefficient in coefficients) {
            arithmetic.step()
            val value = coefficient.rational ?: break
            rational.add(value)
        }
        if (rational.size == coefficients.size) {
            arithmetic.denseSize(polynomial.degree)
            return roots(RationalPolynomial(rational))
        }
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
