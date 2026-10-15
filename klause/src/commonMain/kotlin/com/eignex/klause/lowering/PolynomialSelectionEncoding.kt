package com.eignex.klause.lowering

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation

internal fun polynomialSelectionEncoding(
    selection: PolynomialRealFormula.Selection,
    checkpoint: () -> Unit,
): PolynomialRealFormula {
    checkpoint()
    val result = PolynomialExpression.Variable(selection.result)
    val condition = selection.condition
    if (condition is PolynomialRealFormula.Constant) {
        return PolynomialRealFormula.Atom(result, PolynomialRelation.EQ,
            if (condition.value) selection.whenTrue else selection.whenFalse)
    }
    val whenTrue = PolynomialRealFormula.Atom(result, PolynomialRelation.EQ, selection.whenTrue)
    checkpoint()
    val whenFalse = PolynomialRealFormula.Atom(result, PolynomialRelation.EQ, selection.whenFalse)
    checkpoint()
    return PolynomialRealFormula.And(listOf(
        PolynomialRealFormula.Or(listOf(PolynomialRealFormula.Not(selection.condition), whenTrue)),
        PolynomialRealFormula.Or(listOf(selection.condition, whenFalse)),
    ))
}
