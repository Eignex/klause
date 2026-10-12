package com.eignex.klause.theory.qfnra

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction

internal fun polynomialPointFixings(
    formula: PolynomialRealFormula,
    step: () -> Unit,
): Map<Int, BigFraction> {
    val pending = ArrayDeque<PolynomialRealFormula>()
    val fixed = LinkedHashMap<Int, BigFraction>()
    pending.addLast(formula)
    while (pending.isNotEmpty()) {
        step()
        when (val node = pending.removeLast()) {
            is PolynomialRealFormula.And -> pending.addAll(node.operands)
            is PolynomialRealFormula.Atom -> {
                if (node.relation != PolynomialRelation.EQ) continue
                val variable = (node.left as? PolynomialExpression.Variable)
                    ?: (node.right as? PolynomialExpression.Variable) ?: continue
                val constant = (node.right as? PolynomialExpression.Constant)
                    ?: (node.left as? PolynomialExpression.Constant) ?: continue
                fixed.getOrPut(variable.id.index) { BigFraction.of(constant.numerator, constant.denominator) }
            }
            else -> Unit
        }
    }
    return fixed.toMap()
}
