package com.eignex.klause.theory.qfnia

import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation

internal class NonlinearIntegerResidueReduction(
    private val step: () -> Unit,
    private val reserve: (Int) -> Unit,
    private val excludes: (PolynomialRealFormula.Atom) -> Boolean,
) {
    class Result(val formula: PolynomialRealFormula, val excluded: Long)

    fun reduce(root: PolynomialRealFormula): Result {
        val pending = ArrayDeque<Pair<PolynomialRealFormula, Boolean>>()
        val reduced = HashMap<FormulaIdentity, PolynomialRealFormula>()
        var excluded = 0L
        reserve(1)
        pending.addLast(root to false)
        while (pending.isNotEmpty()) {
            step()
            val (formula, combine) = pending.removeLast()
            val key = FormulaIdentity(formula)
            if (key in reduced) continue
            val count = when (formula) {
                is PolynomialRealFormula.And -> formula.operandCount
                is PolynomialRealFormula.Or -> formula.operandCount
                is PolynomialRealFormula.Not -> 1
                else -> 0
            }
            reserve(count + 2)
            val children = when (formula) {
                is PolynomialRealFormula.And -> formula.operands
                is PolynomialRealFormula.Or -> formula.operands
                is PolynomialRealFormula.Not -> listOf(formula.operand)
                else -> emptyList()
            }
            if (!combine && children.isNotEmpty()) {
                pending.addLast(formula to true)
                for (child in children.asReversed()) pending.addLast(child to false)
                continue
            }
            reduced[key] = when (formula) {
                is PolynomialRealFormula.Atom -> if (formula.relation == PolynomialRelation.EQ && excludes(formula)) {
                    excluded++
                    PolynomialRealFormula.Constant(false)
                } else formula
                is PolynomialRealFormula.Not -> {
                    val child = reduced.getValue(FormulaIdentity(formula.operand))
                    if (child is PolynomialRealFormula.Constant) PolynomialRealFormula.Constant(!child.value)
                    else if (child === formula.operand) formula else PolynomialRealFormula.Not(child)
                }
                is PolynomialRealFormula.And, is PolynomialRealFormula.Or -> {
                    val conjunction = formula is PolynomialRealFormula.And
                    val operands = children.map { step(); reduced.getValue(FormulaIdentity(it)) }
                    if (operands.indices.all { step(); operands[it] === children[it] }) formula
                    else junction(operands, conjunction)
                }
                else -> formula
            }
        }
        step()
        return Result(reduced.getValue(FormulaIdentity(root)), excluded)
    }

    private fun junction(operands: List<PolynomialRealFormula>, conjunction: Boolean): PolynomialRealFormula {
        val retained = ArrayList<PolynomialRealFormula>()
        for (operand in operands) {
            step()
            if (operand is PolynomialRealFormula.Constant) {
                if (operand.value != conjunction) return operand
            } else retained.add(operand)
        }
        return when (retained.size) {
            0 -> PolynomialRealFormula.Constant(conjunction)
            1 -> retained.single()
            else -> if (conjunction) PolynomialRealFormula.And(retained) else PolynomialRealFormula.Or(retained)
        }
    }
}

// Source structural equality can walk shared subtrees outside the bounded traversal.
private class FormulaIdentity(private val formula: PolynomialRealFormula) {
    override fun hashCode(): Int = formula.hashCode()
    override fun equals(other: Any?): Boolean = other is FormulaIdentity && formula === other.formula
}
