package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction

internal class AlgebraicSpecialization(val base: AlgebraicReal, coefficients: List<RationalPolynomial>) {
    private val captured = coefficients.toList()
    val coefficients: List<RationalPolynomial> get() = captured.toList()
}

private typealias FieldPolynomial = List<RationalPolynomial>

internal class CorrelatedAlgebraicRoots(private val algebraic: AlgebraicArithmetic) {
    private val arithmetic = algebraic.arithmetic
    private val rational = RationalPolynomialArithmetic(arithmetic)

    fun roots(specialization: AlgebraicSpecialization): List<AlgebraicReal> {
        val coefficients = specialization.coefficients
        var modulus = rational.squareFree(specialization.base.definingPolynomial)
        var common = modulus
        for (coefficient in coefficients) {
            arithmetic.step()
            common = rational.gcd(common, coefficient)
        }
        // A null polynomial at an unselected conjugate makes the resultant identically zero.
        if (common.degree > 0) modulus = rational.divideExact(modulus, common)
        val reduced = coefficients.map { arithmetic.step(); rational.divide(it, modulus).second }
        val relation = Polynomial.of(buildList {
            for ((degree, coefficient) in reduced.withIndex()) {
                for ((exponent, value) in coefficient.coefficients.withIndex()) {
                    arithmetic.step()
                    add(PolynomialTerm(Monomial.of(mapOf(0 to exponent, 1 to degree)), value))
                }
            }
        })
        val defining = Polynomial.of(modulus.coefficients.mapIndexed { exponent, coefficient ->
            arithmetic.step()
            PolynomialTerm(Monomial.of(mapOf(0 to exponent)), coefficient)
        })
        val resultant = arithmetic.resultant(defining, relation, 0)
        check(resultant.variables.all { it == 1 })
        val annihilator = RationalPolynomial(arithmetic.coefficients(resultant, 1).map {
            arithmetic.step()
            it.coefficient(Monomial.ONE)
        })
        val field = RootField(specialization.base, modulus)
        val sequence = field.sturm(reduced)
        return algebraic.roots(annihilator).filter { candidate ->
            arithmetic.step()
            candidate.rational?.let { field.sign(field.evaluate(reduced, it)) == 0 }
                ?: (field.countOpen(sequence, candidate.interval) == 1)
        }
    }

    private inner class RootField(private val base: AlgebraicReal, private var modulus: RationalPolynomial) {
        private fun reduce(value: RationalPolynomial): RationalPolynomial = rational.divide(value, modulus).second

        fun sign(value: RationalPolynomial): Int {
            arithmetic.step()
            val reduced = reduce(value)
            if (reduced.degree <= 0) return reduced[0].signum()
            val result = algebraic.signAt(reduced, base)
            if (result == 0) {
                // Defining polynomials need not be irreducible; keep the factors containing this root.
                modulus = rational.gcd(modulus, reduced)
            }
            return result
        }

        private fun add(left: RationalPolynomial, right: RationalPolynomial): RationalPolynomial =
            reduce(rational.add(left, right))

        private fun negate(value: RationalPolynomial): RationalPolynomial =
            rational.scale(value, BigFraction.MINUS_ONE)

        private fun multiply(left: RationalPolynomial, right: RationalPolynomial): RationalPolynomial =
            reduce(rational.multiply(reduce(left), reduce(right)))

        private fun inverse(value: RationalPolynomial): RationalPolynomial {
            require(sign(value) != 0)
            while (true) {
                arithmetic.step()
                var a = modulus
                var b = reduce(value)
                var first = RationalPolynomial.ZERO
                var second = RationalPolynomial.ONE
                while (!b.isZero) {
                    arithmetic.step()
                    val (quotient, remainder) = rational.divide(a, b)
                    val next = rational.add(first, negate(rational.multiply(quotient, second)))
                    a = b
                    b = remainder
                    first = second
                    second = next
                }
                if (a.degree == 0) return reduce(rational.scale(first, a[0].reciprocal()))
                // value is nonzero at base, so the common factor cannot contain the selected root.
                modulus = rational.divideExact(modulus, a)
            }
        }

        private fun normalize(value: FieldPolynomial): FieldPolynomial {
            arithmetic.step()
            arithmetic.denseSize(value.lastIndex)
            var size = value.size
            while (size > 0 && sign(value[size - 1]) == 0) size--
            return value.take(size)
        }

        private fun derivative(value: FieldPolynomial): FieldPolynomial = normalize(
            (1 until value.size).map {
                arithmetic.step()
                rational.scale(value[it], BigFraction.ofLong(it.toLong()))
            },
        )

        private fun remainder(left: FieldPolynomial, right: FieldPolynomial): FieldPolynomial {
            val divisor = normalize(right)
            require(divisor.isNotEmpty())
            val leadingInverse = inverse(divisor.last())
            var remaining = normalize(left)
            while (remaining.size >= divisor.size) {
                arithmetic.step()
                val shift = remaining.size - divisor.size
                val coefficient = multiply(remaining.last(), leadingInverse)
                val next = remaining.toMutableList()
                for (index in divisor.indices) {
                    arithmetic.step()
                    next[index + shift] = add(next[index + shift], negate(multiply(coefficient, divisor[index])))
                }
                remaining = normalize(next)
            }
            return remaining
        }

        fun sturm(value: FieldPolynomial): List<FieldPolynomial> {
            val polynomial = normalize(value)
            require(polynomial.size > 1)
            val sequence = mutableListOf(polynomial, derivative(polynomial))
            while (true) {
                arithmetic.step()
                val next = remainder(sequence[sequence.lastIndex - 1], sequence.last())
                if (next.isEmpty()) return sequence
                sequence.add(next.map { arithmetic.step(); negate(it) })
            }
        }

        fun evaluate(value: FieldPolynomial, at: BigFraction): RationalPolynomial {
            var result = RationalPolynomial.ZERO
            for (coefficient in value.asReversed()) {
                arithmetic.step()
                result = add(rational.scale(result, at), coefficient)
            }
            return result
        }

        private fun sideSign(value: FieldPolynomial, at: BigFraction, fromRight: Boolean): Int {
            var polynomial = value
            var order = 0
            while (polynomial.isNotEmpty()) {
                arithmetic.step()
                val sign = sign(evaluate(polynomial, at))
                if (sign != 0) return if (fromRight || order % 2 == 0) sign else -sign
                polynomial = derivative(polynomial)
                order++
            }
            error("zero polynomial in Sturm sequence")
        }

        private fun variations(sequence: List<FieldPolynomial>, at: BigFraction, fromRight: Boolean): Int {
            var previous = 0
            var count = 0
            for (polynomial in sequence) {
                arithmetic.step()
                val sign = sideSign(polynomial, at, fromRight)
                if (previous != 0 && sign != previous) count++
                previous = sign
            }
            return count
        }

        fun countOpen(sequence: List<FieldPolynomial>, interval: RationalInterval): Int {
            arithmetic.step()
            val count = variations(sequence, interval.lower, true) - variations(sequence, interval.upper, false)
            check(count in 0..1) { "specialized roots escaped their annihilator" }
            return count
        }
    }
}
