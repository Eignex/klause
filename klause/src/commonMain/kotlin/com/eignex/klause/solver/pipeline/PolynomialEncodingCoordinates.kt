package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.util.Cancellation

internal fun validatePolynomialEncodingCoordinates(
    formula: PolynomialRealFormula?,
    expression: PolynomialExpression?,
    realIds: Set<Int>,
    booleanIds: Set<Int>,
    cancellation: Cancellation,
) {
    val pending = ArrayDeque<Any>()
    formula?.let(pending::addLast)
    expression?.let(pending::addLast)
    while (pending.isNotEmpty()) {
        if (cancellation()) throw PolynomialSourceInterrupted()
        cancellation.charge(1)
        when (val node = pending.removeLast()) {
            is PolynomialRealFormula.Constant, is PolynomialExpression.Constant -> Unit
            is PolynomialRealFormula.BooleanVariable -> require(node.id.index in booleanIds) {
                "foreign Boolean coordinate in polynomial encoding"
            }
            is PolynomialExpression.Variable -> require(node.id.index in realIds) {
                "unattributed coordinate in polynomial encoding"
            }
            is PolynomialRealFormula.Atom -> {
                pending.addLast(node.left)
                pending.addLast(node.right)
            }
            is PolynomialRealFormula.Not -> pending.addLast(node.operand)
            is PolynomialExpression.Power -> pending.addLast(node.base)
            is PolynomialRealFormula.And -> node.operands.forEach(pending::addLast)
            is PolynomialRealFormula.Or -> node.operands.forEach(pending::addLast)
            is PolynomialExpression.Sum -> node.operands.forEach(pending::addLast)
            is PolynomialExpression.Product -> node.operands.forEach(pending::addLast)
        }
    }
    if (cancellation()) throw PolynomialSourceInterrupted()
}
