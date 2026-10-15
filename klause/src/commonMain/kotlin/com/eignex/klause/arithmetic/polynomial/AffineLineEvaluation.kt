package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.magnitudeBitLength

internal class AffineLineEvaluation(
    base: List<BigFraction>,
    direction: List<BigFraction>,
    private val algebraic: AlgebraicArithmetic,
) {
    private val arithmetic = algebraic.arithmetic
    private val univariate = RationalPolynomialArithmetic(arithmetic)

    init {
        arithmetic.step()
        require(base.size == direction.size)
    }

    private val coordinates = List(base.size) { id ->
        arithmetic.step()
        RationalPolynomial(listOf(base[id], direction[id]))
    }
    private val powers = HashMap<Pair<Int, Int>, RationalPolynomial>()
    private val images = HashMap<Polynomial, RationalPolynomial>()

    fun at(parameter: AlgebraicReal): Point = Point(parameter)

    inner class Point internal constructor(private val parameter: AlgebraicReal) {
        init { arithmetic.step() }

        val coordinateCount: Int get() = coordinates.size

        fun coordinate(variable: Int): AlgebraicReal = algebraic.evaluate(coordinates[variable], parameter)

        fun sign(polynomial: Polynomial): Int = algebraic.signAt(image(polynomial), parameter)
    }

    private fun image(polynomial: Polynomial): RationalPolynomial {
        arithmetic.step()
        images[polynomial]?.let { return it }
        var result = RationalPolynomial.ZERO
        for ((monomial, coefficient) in polynomial.terms) {
            arithmetic.step()
            var term = RationalPolynomial(listOf(coefficient))
            for (variable in monomial.variables) {
                term = univariate.multiply(term, power(variable, monomial.exponent(variable)))
            }
            result = univariate.add(result, term)
        }
        arithmetic.step()
        images[polynomial] = result
        return result
    }

    private fun power(variable: Int, exponent: Int): RationalPolynomial {
        arithmetic.step()
        require(variable in coordinates.indices && exponent >= 0)
        val key = variable to exponent
        powers[key]?.let { return it }
        var base = coordinates[variable]
        if (base.degree <= 0) {
            val coefficient = base[0]
            val minimumBits = maxOf(coefficient.num.magnitudeBitLength(), coefficient.den.magnitudeBitLength()) - 1L
            if (minimumBits * exponent + 1 > Int.MAX_VALUE) {
                throw PolynomialResourceLimit(PolynomialResource.DEGREE_OVERFLOW)
            }
        }
        var result = RationalPolynomial.ONE
        var remaining = exponent
        while (remaining != 0) {
            arithmetic.step()
            if (remaining and 1 != 0) result = univariate.multiply(result, base)
            remaining = remaining ushr 1
            if (remaining != 0) base = univariate.multiply(base, base)
        }
        arithmetic.step()
        powers[key] = result
        return result
    }
}
