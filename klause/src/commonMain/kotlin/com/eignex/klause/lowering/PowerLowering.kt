package com.eignex.klause.lowering

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.util.BIG_ONE

/**
 * Equivalent multiplication DAG for a consumer without a native power operation.
 * Repeated operands remain repeated product roles; shared squaring nodes bound syntax size.
 * The returned expression uses the caller's coordinate scope and allocates no source variables.
 */
fun powerAsProducts(base: PolynomialExpression, exponent: Int): PolynomialExpression {
    require(exponent >= 0) { "Power requires a nonnegative integer exponent" }
    var remaining = exponent
    var value: PolynomialExpression? = null
    var factor = base
    while (remaining > 0) {
        if (remaining and 1 != 0) value = value?.let { PolynomialExpression.Product(listOf(it, factor)) } ?: factor
        remaining = remaining ushr 1
        if (remaining > 0) factor = PolynomialExpression.Product(listOf(factor, factor))
    }
    return value ?: PolynomialExpression.Constant.of(BIG_ONE)
}
