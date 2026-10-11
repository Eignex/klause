package com.eignex.klause.cli

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.times

internal fun smtAlgebraicValue(value: AlgebraicReal): String = value.rational?.let {
    smtReal(it.toString())
} ?: smtPolynomialRoot(value.definingCoefficients, value.rootIndex)

internal fun smtPolynomialRoot(coefficients: List<BigFraction>, rootIndex: Int): String {
    require(rootIndex > 0)
    var denominator = BIG_ONE
    for (coefficient in coefficients) {
        denominator = denominator / denominator.gcd(coefficient.den) * coefficient.den
    }
    val terms = coefficients.mapIndexedNotNull { exponent, coefficient ->
        if (coefficient.isZero) return@mapIndexedNotNull null
        val integer = coefficient.num * (denominator / coefficient.den)
        val number = smtInteger(integer.toString())
        when (exponent) {
            0 -> number
            1 -> "(* $number x)"
            else -> "(* $number (^ x $exponent))"
        }
    }
    require(terms.isNotEmpty())
    val polynomial = if (terms.size == 1) terms.single() else "(+ ${terms.joinToString(" ")})"
    return "(root-obj $polynomial $rootIndex)"
}

internal fun smtSourceSymbol(name: String): String {
    require(name.none { it == '|' || it == '\\' })
    return "|$name|"
}
