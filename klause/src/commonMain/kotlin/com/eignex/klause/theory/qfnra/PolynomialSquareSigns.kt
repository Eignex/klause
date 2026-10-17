package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialSigns.ALL_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.NEGATIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.NONZERO_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.POSITIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.ZERO_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.multiplySigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.negateSigns

internal fun preparePolynomialSquareSigns(
    polynomial: Polynomial,
    variableSigns: Map<Int, Int>,
    arithmetic: PolynomialArithmetic,
    orientation: BigFraction = BigFraction.ONE,
): Iterator<Int?> = sequence {
    val signs = variableSigns.mapValues { arithmetic.step(); it.value }
    val coefficients = LinkedHashMap<Monomial, BigFraction>()
    for (term in polynomial.terms) {
        arithmetic.step()
        coefficients[term.monomial] = term.coefficient * orientation
    }
    var squares = false
    var batch = 0
    for (negative in coefficients.keys.toList()) {
        arithmetic.step()
        if (coefficients.getValue(negative).signum() < 0) for (positive in coefficients.keys.toList()) {
            arithmetic.step()
            if (++batch == 64) {
                batch = 0
                yield(null)
            }
            val left = coefficients.getValue(positive)
            if (left.signum() <= 0) continue
            val reflected = reflectedMonomial(negative, positive, arithmetic) ?: continue
            if (reflected == positive) continue
            val right = coefficients[reflected] ?: continue
            if (right.signum() <= 0) continue
            val common = commonSquareFactor(positive, reflected, arithmetic)
            if (certificateMonomialSigns(common, signs, arithmetic) and NEGATIVE_SIGN != 0) continue
            arithmetic.step()
            val needed = coefficients.getValue(negative).negated() * BigFraction.ofLong(2).reciprocal()
            var amount = if (left < right) left else right
            if (needed < amount) amount = needed
            coefficients[positive] = left - amount
            coefficients[reflected] = right - amount
            coefficients[negative] = coefficients.getValue(negative) + amount + amount
            squares = true
            if (coefficients.getValue(negative).isZero) break
        }
        yield(null)
    }
    var sum = if (squares) ZERO_SIGN or POSITIVE_SIGN else ZERO_SIGN
    for ((monomial, coefficient) in coefficients) {
        arithmetic.step()
        if (coefficient.isZero) continue
        val term = certificateMonomialSigns(monomial, signs, arithmetic)
        sum = certificateSumSigns(sum, if (coefficient.signum() > 0) term else negateSigns(term))
    }
    arithmetic.step()
    yield(sum)
}.iterator()

private fun reflectedMonomial(center: Monomial, side: Monomial, arithmetic: PolynomialArithmetic): Monomial? {
    for (variable in side.variables) {
        arithmetic.step()
        if (center.exponent(variable) == 0) return null
    }
    val powers = LinkedHashMap<Int, Int>()
    for (variable in center.variables) {
        arithmetic.step()
        val degree = 2L * center.exponent(variable) - side.exponent(variable)
        if (degree !in 0L..Int.MAX_VALUE.toLong()) return null
        if (degree > 0) powers[variable] = degree.toInt()
    }
    return Monomial.of(powers)
}

private fun commonSquareFactor(left: Monomial, right: Monomial, arithmetic: PolynomialArithmetic): Monomial {
    val powers = LinkedHashMap<Int, Int>()
    for (variable in left.variables) {
        arithmetic.step()
        val degree = minOf(left.exponent(variable), right.exponent(variable))
        if (degree > 0) powers[variable] = degree
    }
    return Monomial.of(powers)
}

private fun certificateMonomialSigns(
    monomial: Monomial,
    signs: Map<Int, Int>,
    arithmetic: PolynomialArithmetic,
): Int {
    var product = POSITIVE_SIGN
    for (variable in monomial.variables) {
        arithmetic.step()
        var factor = signs[variable] ?: ALL_SIGNS
        if (monomial.exponent(variable) and 1 == 0) {
            factor = (factor and ZERO_SIGN) or if (factor and NONZERO_SIGNS == 0) 0 else POSITIVE_SIGN
        }
        product = multiplySigns(product, factor)
    }
    return product
}

private fun certificateSumSigns(left: Int, right: Int): Int {
    if ((left and NEGATIVE_SIGN != 0 && right and POSITIVE_SIGN != 0) ||
        (left and POSITIVE_SIGN != 0 && right and NEGATIVE_SIGN != 0)
    ) return ALL_SIGNS
    return (left and right and ZERO_SIGN) or ((left or right) and NONZERO_SIGNS)
}
