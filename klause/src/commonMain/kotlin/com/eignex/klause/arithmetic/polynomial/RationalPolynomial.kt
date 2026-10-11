package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction

internal class RationalPolynomial(coefficients: List<BigFraction>) {
    private val captured = coefficients.dropLastWhile { it.isZero }
    val coefficients: List<BigFraction> get() = captured.toList()
    val degree: Int get() = captured.lastIndex
    val isZero: Boolean get() = captured.isEmpty()
    operator fun get(degree: Int): BigFraction = captured.getOrElse(degree) { BigFraction.ZERO }

    override fun equals(other: Any?): Boolean = other is RationalPolynomial && captured == other.captured
    override fun hashCode(): Int = captured.hashCode()

    companion object {
        val ZERO = RationalPolynomial(emptyList())
        val ONE = RationalPolynomial(listOf(BigFraction.ONE))
        val X = RationalPolynomial(listOf(BigFraction.ZERO, BigFraction.ONE))
        fun of(vararg coefficients: Long): RationalPolynomial =
            RationalPolynomial(coefficients.map(BigFraction::ofLong))
    }
}

internal data class RationalInterval(val lower: BigFraction, val upper: BigFraction) {
    init { require(lower <= upper) }
    val isPoint: Boolean get() = lower == upper
}

internal data class SquareFreeFactor(val polynomial: RationalPolynomial, val multiplicity: Int)

internal class RationalPolynomialArithmetic(val arithmetic: PolynomialArithmetic = PolynomialArithmetic()) {
    fun add(left: RationalPolynomial, right: RationalPolynomial): RationalPolynomial = RationalPolynomial(
        List(arithmetic.denseSize(maxOf(left.degree, right.degree))) {
            arithmetic.step()
            left[it] + right[it]
        },
    )

    fun scale(value: RationalPolynomial, coefficient: BigFraction): RationalPolynomial = RationalPolynomial(
        value.coefficients.map {
            arithmetic.step()
            it * coefficient
        },
    )

    fun multiply(left: RationalPolynomial, right: RationalPolynomial): RationalPolynomial {
        arithmetic.step()
        if (left.isZero || right.isZero) return RationalPolynomial.ZERO
        if (left.degree > Int.MAX_VALUE - right.degree) {
            throw PolynomialResourceLimit(PolynomialResource.DEGREE_OVERFLOW)
        }
        val result = MutableList(arithmetic.denseSize(left.degree + right.degree)) { BigFraction.ZERO }
        for (i in 0..left.degree) for (j in 0..right.degree) {
            arithmetic.step()
            result[i + j] = result[i + j] + left[i] * right[j]
        }
        return RationalPolynomial(result)
    }

    fun derivative(value: RationalPolynomial): RationalPolynomial = RationalPolynomial(
        (1..value.degree).map {
            arithmetic.step()
            value[it] * BigFraction.ofLong(it.toLong())
        },
    )

    fun evaluate(value: RationalPolynomial, at: BigFraction): BigFraction {
        var result = BigFraction.ZERO
        for (coefficient in value.coefficients.asReversed()) {
            arithmetic.step()
            result = result * at + coefficient
        }
        return result
    }

    fun divide(left: RationalPolynomial, right: RationalPolynomial): Pair<RationalPolynomial, RationalPolynomial> {
        require(!right.isZero)
        val quotient = MutableList((left.degree - right.degree + 1).coerceAtLeast(0)) { BigFraction.ZERO }
        var remainder = left
        while (!remainder.isZero && remainder.degree >= right.degree) {
            arithmetic.step()
            val shift = remainder.degree - right.degree
            val coefficient = remainder[remainder.degree] * right[right.degree].reciprocal()
            quotient[shift] = coefficient
            val terms = remainder.coefficients.toMutableList()
            for (i in 0..right.degree) {
                arithmetic.step()
                terms[shift + i] = terms[shift + i] - coefficient * right[i]
            }
            remainder = RationalPolynomial(terms)
        }
        return RationalPolynomial(quotient) to remainder
    }

    fun divideExact(left: RationalPolynomial, right: RationalPolynomial): RationalPolynomial {
        val (quotient, remainder) = divide(left, right)
        require(remainder.isZero) { "nonexact polynomial division" }
        return quotient
    }

    fun monic(value: RationalPolynomial): RationalPolynomial =
        if (value.isZero) value else scale(value, value[value.degree].reciprocal())

    fun gcd(left: RationalPolynomial, right: RationalPolynomial): RationalPolynomial {
        var a = left
        var b = right
        while (!b.isZero) {
            arithmetic.step()
            val remainder = divide(a, b).second
            a = b
            b = remainder
        }
        return monic(a)
    }

    fun squareFree(value: RationalPolynomial): RationalPolynomial =
        if (value.degree <= 0) monic(value) else monic(divideExact(value, gcd(value, derivative(value))))

    fun squareFreeFactors(value: RationalPolynomial): List<SquareFreeFactor> {
        arithmetic.step()
        if (value.degree <= 0) return emptyList()
        var common = gcd(value, derivative(value))
        var remaining = divideExact(monic(value), common)
        var multiplicity = 1
        val result = mutableListOf<SquareFreeFactor>()
        while (remaining.degree > 0) {
            arithmetic.step()
            val shared = gcd(remaining, common)
            val factor = divideExact(remaining, shared)
            if (factor.degree > 0) result.add(SquareFreeFactor(factor, multiplicity))
            remaining = shared
            common = divideExact(common, shared)
            multiplicity++
        }
        return result
    }

    fun sturm(value: RationalPolynomial): List<RationalPolynomial> {
        require(value.degree > 0)
        val result = mutableListOf(squareFree(value))
        result.add(derivative(result[0]))
        while (!result.last().isZero) {
            arithmetic.step()
            val remainder = divide(result[result.lastIndex - 1], result.last()).second
            if (remainder.isZero) break
            result.add(scale(remainder, BigFraction.MINUS_ONE))
        }
        return result
    }

    private fun variations(sequence: List<RationalPolynomial>, at: BigFraction): Int {
        var previous = 0
        var result = 0
        for (polynomial in sequence) {
            val sign = evaluate(polynomial, at).signum()
            if (sign == 0) continue
            if (previous != 0 && previous != sign) result++
            previous = sign
        }
        return result
    }

    fun countOpen(sequence: List<RationalPolynomial>, interval: RationalInterval): Int {
        if (interval.isPoint) return 0
        return variations(sequence, interval.lower) - variations(sequence, interval.upper) -
            if (evaluate(sequence[0], interval.upper).isZero) 1 else 0
    }

    fun isolate(value: RationalPolynomial): List<RationalInterval> {
        arithmetic.step()
        require(!value.isZero) { "zero polynomial has no finite root list" }
        if (value.degree == 0) return emptyList()
        val polynomial = squareFree(value)
        if (polynomial.degree == 1) {
            val root = polynomial[0].negated() * polynomial[1].reciprocal()
            return listOf(RationalInterval(root, root))
        }
        val sequence = sturm(polynomial)
        var bound = BigFraction.ONE
        val leading = polynomial[polynomial.degree].reciprocal()
        for (coefficient in polynomial.coefficients.dropLast(1)) {
            val ratio = coefficient * leading
            val limit = (if (ratio.signum() < 0) ratio.negated() else ratio) + BigFraction.ONE
            while (bound <= limit) {
                arithmetic.step()
                bound = bound + bound
            }
        }
        val pending = mutableListOf(RationalInterval(bound.negated(), bound))
        val roots = mutableListOf<RationalInterval>()
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val interval = pending.removeAt(pending.lastIndex)
            val count = countOpen(sequence, interval)
            if (count == 0) continue
            val lowerRoot = evaluate(polynomial, interval.lower).isZero
            val upperRoot = evaluate(polynomial, interval.upper).isZero
            if (count == 1 && !lowerRoot && !upperRoot) {
                roots.add(interval)
                continue
            }
            val midpoint = (interval.lower + interval.upper) * BigFraction.ofLong(2).reciprocal()
            if (evaluate(polynomial, midpoint).isZero) roots.add(RationalInterval(midpoint, midpoint))
            pending.add(RationalInterval(midpoint, interval.upper))
            pending.add(RationalInterval(interval.lower, midpoint))
        }
        return roots.sortedWith { left, right -> left.lower.compareTo(right.lower) }
    }

    fun refine(polynomial: RationalPolynomial, interval: RationalInterval): RationalInterval {
        arithmetic.step()
        if (interval.isPoint) return interval
        val midpoint = (interval.lower + interval.upper) * BigFraction.ofLong(2).reciprocal()
        if (evaluate(polynomial, midpoint).isZero) return RationalInterval(midpoint, midpoint)
        val left = RationalInterval(interval.lower, midpoint)
        return if (countOpen(sturm(polynomial), left) == 1) left else RationalInterval(midpoint, interval.upper)
    }
}
