package com.eignex.klause.cli

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.formats.smtlib.smtAlgebraicTerm
import com.eignex.klause.formats.smtlib.smtRootTerm
import com.eignex.klause.simplex.exact.BigFraction

internal fun smtAlgebraicValue(value: AlgebraicReal): String = smtAlgebraicTerm(value)

internal fun smtPolynomialRoot(coefficients: List<BigFraction>, rootIndex: Int): String =
    smtRootTerm(coefficients, rootIndex)

internal fun smtSourceSymbol(name: String): String {
    require(name.none { it == '|' || it == '\\' })
    return "|$name|"
}
