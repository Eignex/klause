package com.eignex.klause.lowering

import com.eignex.klause.ir.PolynomialDistinct
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.PolynomialSelection
import com.eignex.klause.ir.TranscendentalDistinct
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.util.ExecutionControl

internal fun PolynomialRealFormula.functionFormula(cancellation: ExecutionControl): TranscendentalFormula {
    val expressions = HashMap<PolynomialExpression, TranscendentalExpression>()
    fun expression(root: PolynomialExpression): TranscendentalExpression {
        val work = ArrayDeque<Pair<PolynomialExpression, Boolean>>()
        work.addLast(root to false)
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint(1)
            val (node, combine) = work.removeLast()
            if (node in expressions) continue
            val children = when (node) {
                is PolynomialExpression.Sum -> node.operands
                is PolynomialExpression.Product -> node.operands
                is PolynomialExpression.Power -> listOf(node.base)
                else -> emptyList()
            }
            if (!combine && children.isNotEmpty()) {
                work.addLast(node to true)
                children.asReversed().forEach { work.addLast(it to false) }
                continue
            }
            expressions[node] = when (node) {
                is PolynomialExpression.Constant -> TranscendentalExpression.Constant(node)
                is PolynomialExpression.Variable -> TranscendentalExpression.Variable(node.id)
                is PolynomialExpression.Sum -> TranscendentalExpression.Sum(children.map(expressions::getValue))
                is PolynomialExpression.Product -> TranscendentalExpression.Product(children.map(expressions::getValue))
                is PolynomialExpression.Power -> TranscendentalExpression.Power(expressions.getValue(node.base),
                    node.exponent)
            }
        }
        return expressions.getValue(root)
    }
    val formulas = HashMap<PolynomialRealFormula, TranscendentalFormula>()
    val work = ArrayDeque<Pair<PolynomialRealFormula, Boolean>>()
    work.addLast(this to false)
    while (work.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val (node, combine) = work.removeLast()
        if (node in formulas) continue
        val children = when (node) {
            is PolynomialRealFormula.And -> node.operands
            is PolynomialRealFormula.Or -> node.operands
            is PolynomialRealFormula.Not -> listOf(node.operand)
            is PolynomialSelection -> listOf(node.condition)
            else -> emptyList()
        }
        if (!combine && children.isNotEmpty()) {
            work.addLast(node to true)
            children.asReversed().forEach { work.addLast(it to false) }
            continue
        }
        formulas[node] = when (node) {
            is PolynomialRealFormula.Constant -> TranscendentalFormula.Constant(node.value)
            is PolynomialRealFormula.BooleanVariable -> TranscendentalFormula.BooleanVariable(node.id)
            is PolynomialRealFormula.Atom -> TranscendentalFormula.Atom(expression(node.left), node.relation,
                expression(node.right))
            is PolynomialRealFormula.Not -> TranscendentalFormula.Not(formulas.getValue(node.operand))
            is PolynomialRealFormula.And -> TranscendentalFormula.And(children.map(formulas::getValue))
            is PolynomialRealFormula.Or -> TranscendentalFormula.Or(children.map(formulas::getValue))
            is PolynomialDistinct -> TranscendentalDistinct(node.operands.map(::expression))
            is PolynomialSelection -> TranscendentalFormula.Atom(TranscendentalExpression.Variable(node.result),
                PolynomialRelation.EQ, TranscendentalExpression.Conditional(formulas.getValue(node.condition),
                    expression(node.whenTrue), expression(node.whenFalse)))
        }
    }
    return formulas.getValue(this)
}
