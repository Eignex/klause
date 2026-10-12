package com.eignex.klause.solver.objective

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable

internal fun QuadraticObjectiveSpec.evaluateExact(
    arithmetic: AlgebraicArithmetic,
    coordinate: (QuadraticVariable) -> AlgebraicReal,
): AlgebraicReal {
    var value = arithmetic.rational(constant)
    for (term in terms) {
        arithmetic.arithmetic.step()
        var product = coordinate(term.first)
        term.second?.let { product = arithmetic.multiply(product, coordinate(it)) }
        value = arithmetic.add(value, arithmetic.multiply(arithmetic.rational(term.coefficient), product))
    }
    arithmetic.arithmetic.step()
    return value
}
