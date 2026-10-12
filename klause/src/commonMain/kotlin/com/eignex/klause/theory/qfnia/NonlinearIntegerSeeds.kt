package com.eignex.klause.theory.qfnia

import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

internal fun NonlinearIntegerProblem.integerSeeds(
    cancellation: Cancellation,
    step: () -> Unit = {},
): List<BigFraction> {
    val values = integerCoordinates.associateWith { BigFraction.ZERO }.toMutableMap()
    val seen = HashSet<PolynomialRealFormula>()
    val work = ArrayDeque<PolynomialRealFormula>()
    work.addLast(formula)
    while (work.isNotEmpty() && !cancellation()) {
        step()
        val node = work.removeLast()
        if (!seen.add(node)) continue
        when (node) {
            is PolynomialRealFormula.Atom -> if (node.relation == PolynomialRelation.EQ) {
                fun seed(variable: PolynomialExpression, constant: PolynomialExpression) {
                    if (variable !is PolynomialExpression.Variable || constant !is PolynomialExpression.Constant) return
                    if (variable.id.index in values && constant.denominator == BIG_ONE) {
                        values[variable.id.index] = BigFraction.of(constant.numerator, BIG_ONE)
                    }
                }
                seed(node.left, node.right)
                seed(node.right, node.left)
            }
            is PolynomialRealFormula.And -> work.addAll(node.operands)
            is PolynomialRealFormula.Or -> work.addAll(node.operands)
            is PolynomialRealFormula.Not -> work.addLast(node.operand)
            else -> Unit
        }
    }
    // Equalities under Boolean alternatives supply proposals, never assumed source bounds.
    return integerCoordinates.map(values::getValue)
}
