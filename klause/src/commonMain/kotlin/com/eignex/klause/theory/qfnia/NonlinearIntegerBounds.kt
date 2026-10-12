package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.unaryMinus

internal fun NonlinearIntegerProblem.integerBounds(
    condition: PolynomialCondition,
    arithmetic: PolynomialArithmetic,
): PolynomialCondition {
    val integers = integerCoordinates.toSet()
    val lower = condition.lower.toMutableMap()
    val upper = condition.upper.toMutableMap()
    fun bound(id: Int, value: BigFraction, minimum: Boolean) {
        val bounds = if (minimum) lower else upper
        val old = bounds[id]
        if (old == null || if (minimum) value > old else value < old) bounds[id] = value
    }
    fun atom(variable: PolynomialExpression, relation: PolynomialRelation, constant: PolynomialExpression) {
        if (variable !is PolynomialExpression.Variable || variable.id.index !in integers ||
            constant !is PolynomialExpression.Constant
        ) return
        val value = BigFraction.of(constant.numerator, constant.denominator)
        val ceiling = value.ceilInteger()
        val floor = -value.negated().ceilInteger()
        when (relation) {
            PolynomialRelation.EQ -> {
                bound(variable.id.index, BigFraction.of(ceiling, BIG_ONE), true)
                bound(variable.id.index, BigFraction.of(floor, BIG_ONE), false)
            }
            PolynomialRelation.LE -> bound(variable.id.index, BigFraction.of(floor, BIG_ONE), false)
            PolynomialRelation.LT -> bound(variable.id.index, BigFraction.of(ceiling - BIG_ONE, BIG_ONE), false)
            PolynomialRelation.GE -> bound(variable.id.index, BigFraction.of(ceiling, BIG_ONE), true)
            PolynomialRelation.GT -> bound(variable.id.index, BigFraction.of(floor + BIG_ONE, BIG_ONE), true)
            PolynomialRelation.NE -> Unit
        }
    }
    for (id in integers) {
        arithmetic.step()
        condition.lower[id]?.let { bound(id, BigFraction.of(it.ceilInteger(), BIG_ONE), true) }
        condition.upper[id]?.let { bound(id, BigFraction.of(-it.negated().ceilInteger(), BIG_ONE), false) }
    }
    val work = ArrayDeque<PolynomialRealFormula>()
    work.addLast(formula)
    while (work.isNotEmpty()) {
        arithmetic.step()
        when (val node = work.removeLast()) {
            is PolynomialRealFormula.And -> work.addAll(node.operands)
            is PolynomialRealFormula.Atom -> {
                atom(node.left, node.relation, node.right)
                val reversed = when (node.relation) {
                    PolynomialRelation.LE -> PolynomialRelation.GE
                    PolynomialRelation.LT -> PolynomialRelation.GT
                    PolynomialRelation.GE -> PolynomialRelation.LE
                    PolynomialRelation.GT -> PolynomialRelation.LT
                    else -> node.relation
                }
                atom(node.right, reversed, node.left)
            }
            else -> Unit
        }
    }
    return PolynomialCondition(condition.bools, lower, upper)
}
