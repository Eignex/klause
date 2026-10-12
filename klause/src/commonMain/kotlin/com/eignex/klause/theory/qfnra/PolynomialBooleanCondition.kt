package com.eignex.klause.theory.qfnra

import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.lowering.PolynomialRealLowering

internal object PolynomialBooleanCondition {
    fun apply(
        formula: PolynomialRealFormula,
        bools: Map<Int, Boolean>,
        step: () -> Unit,
    ): PolynomialRealFormula {
        if (bools.isEmpty()) return formula
        val values = HashMap<PolynomialRealFormula, PolynomialRealFormula>()
        val work = ArrayDeque<Pair<PolynomialRealFormula, Boolean>>()
        work.addLast(formula to false)
        while (work.isNotEmpty()) {
            step()
            val (node, combine) = work.removeLast()
            if (node in values) continue
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
            values[node] = when (node) {
                is PolynomialRealFormula.BooleanVariable -> bools[node.id.index]
                    ?.let { PolynomialRealFormula.Constant(it) } ?: node
                is PolynomialRealFormula.Not -> PolynomialRealLowering.not(values.getValue(node.operand))
                is PolynomialRealFormula.And -> PolynomialRealLowering.and(children.map(values::getValue))
                is PolynomialRealFormula.Or -> PolynomialRealLowering.or(children.map(values::getValue))
                else -> node
            }
        }
        step()
        return values.getValue(formula)
    }
}
