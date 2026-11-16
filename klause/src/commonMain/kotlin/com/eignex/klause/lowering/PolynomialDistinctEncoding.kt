package com.eignex.klause.lowering

import com.eignex.klause.ir.PolynomialDistinct
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation

internal fun polynomialDistinctEncoding(
    distinct: PolynomialDistinct,
    checkpoint: () -> Unit,
): PolynomialRealFormula {
    checkpoint()
    val operands = distinct.operands
    val comparisons = ArrayList<PolynomialRealFormula>()
    for (first in operands.indices) {
        for (second in first + 1 until operands.size) {
            checkpoint()
            comparisons += PolynomialRealFormula.Atom(operands[first], PolynomialRelation.NE, operands[second])
        }
    }
    checkpoint()
    return PolynomialRealFormula.And(comparisons)
}
