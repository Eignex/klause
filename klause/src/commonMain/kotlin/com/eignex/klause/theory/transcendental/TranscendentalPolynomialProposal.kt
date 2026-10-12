package com.eignex.klause.theory.transcendental

import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula

internal fun polynomialProposal(
    source: TranscendentalFormula,
    arithmetic: TranscendentalArithmetic,
): PolynomialRealFormula {
    val atoms = ArrayList<PolynomialRealFormula>()
    val pending = ArrayDeque<TranscendentalFormula>()
    pending.addLast(source)
    while (pending.isNotEmpty()) {
        arithmetic.step()
        when (val node = pending.removeLast()) {
            is TranscendentalFormula.And -> pending.addAll(node.operands)
            is TranscendentalFormula.Atom -> {
                val left = polynomial(node.left, arithmetic)
                val right = polynomial(node.right, arithmetic)
                if (left != null && right != null) atoms.add(PolynomialRealFormula.Atom(left, node.relation, right))
            }
            is TranscendentalFormula.BooleanVariable -> atoms.add(PolynomialRealFormula.BooleanVariable(node.id))
            is TranscendentalFormula.Constant -> atoms.add(PolynomialRealFormula.Constant(node.value))
            else -> Unit
        }
    }
    return PolynomialRealFormula.And(atoms)
}

private fun polynomial(
    expression: TranscendentalExpression,
    arithmetic: TranscendentalArithmetic,
): PolynomialExpression? {
    val values = HashMap<TranscendentalExpression, PolynomialExpression>()
    val pending = ArrayDeque<Pair<TranscendentalExpression, Boolean>>()
    pending.addLast(expression to false)
    while (pending.isNotEmpty()) {
        arithmetic.step()
        val (node, combine) = pending.removeLast()
        if (node in values) continue
        val children = when (node) {
            is TranscendentalExpression.Sum -> node.operands
            is TranscendentalExpression.Product -> node.operands
            is TranscendentalExpression.Power -> listOf(node.base)
            else -> emptyList()
        }
        if (!combine && children.isNotEmpty()) {
            pending.addLast(node to true)
            children.asReversed().forEach { pending.addLast(it to false) }
            continue
        }
        values[node] = when (node) {
            is TranscendentalExpression.Constant -> node.value
            is TranscendentalExpression.Variable -> PolynomialExpression.Variable(node.id)
            is TranscendentalExpression.Sum -> PolynomialExpression.Sum(children.map(values::getValue))
            is TranscendentalExpression.Product -> PolynomialExpression.Product(children.map(values::getValue))
            is TranscendentalExpression.Power -> PolynomialExpression.Power(values.getValue(node.base), node.exponent)
            else -> return null
        }
    }
    return values.getValue(expression)
}
