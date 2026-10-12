package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.QfNraAssignment

internal fun checkNonlinearSource(
    formula: PolynomialRealFormula,
    assignment: QfNraAssignment,
    arithmetic: PolynomialArithmetic,
): Boolean {
    val algebraic = AlgebraicArithmetic(arithmetic)
    val expressions = HashMap<PolynomialExpression, AlgebraicReal>()
    fun evaluate(root: PolynomialExpression): AlgebraicReal {
        val work = ArrayDeque<Pair<PolynomialExpression, Boolean>>()
        work.addLast(root to false)
        while (work.isNotEmpty()) {
            arithmetic.step()
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
                for (child in children.asReversed()) work.addLast(child to false)
                continue
            }
            expressions[node] = when (node) {
                is PolynomialExpression.Constant -> algebraic.rational(BigFraction.of(node.numerator, node.denominator))
                is PolynomialExpression.Variable -> assignment.realValue(node.id.index)
                is PolynomialExpression.Sum -> children.fold(algebraic.rational(BigFraction.ZERO)) { value, child ->
                    algebraic.add(value, expressions.getValue(child))
                }
                is PolynomialExpression.Product -> children.fold(algebraic.rational(BigFraction.ONE)) { value, child ->
                    algebraic.multiply(value, expressions.getValue(child))
                }
                is PolynomialExpression.Power -> algebraic.power(expressions.getValue(node.base), node.exponent)
            }
        }
        return expressions.getValue(root)
    }
    val truth = HashMap<PolynomialRealFormula, Boolean>()
    val work = ArrayDeque<Pair<PolynomialRealFormula, Boolean>>()
    work.addLast(formula to false)
    while (work.isNotEmpty()) {
        arithmetic.step()
        val (node, combine) = work.removeLast()
        if (node in truth) continue
        val children = when (node) {
            is PolynomialRealFormula.And -> node.operands
            is PolynomialRealFormula.Or -> node.operands
            is PolynomialRealFormula.Not -> listOf(node.operand)
            else -> emptyList()
        }
        if (!combine && children.isNotEmpty()) {
            work.addLast(node to true)
            for (child in children.asReversed()) work.addLast(child to false)
            continue
        }
        truth[node] = when (node) {
            is PolynomialRealFormula.Constant -> node.value
            is PolynomialRealFormula.BooleanVariable -> assignment.boolValue(node.id.index)
            is PolynomialRealFormula.Not -> !truth.getValue(node.operand)
            is PolynomialRealFormula.And -> children.all { truth.getValue(it) }
            is PolynomialRealFormula.Or -> children.any { truth.getValue(it) }
            is PolynomialRealFormula.Atom -> {
                val comparison = algebraic.compare(evaluate(node.left), evaluate(node.right))
                when (node.relation) {
                    PolynomialRelation.EQ -> comparison == 0
                    PolynomialRelation.NE -> comparison != 0
                    PolynomialRelation.LT -> comparison < 0
                    PolynomialRelation.LE -> comparison <= 0
                    PolynomialRelation.GT -> comparison > 0
                    PolynomialRelation.GE -> comparison >= 0
                }
            }
        }
    }
    arithmetic.step()
    return truth.getValue(formula)
}
