package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.simplex.exact.BigFraction

internal fun ContinuousProduct.emitLpRelaxation(builder: RelaxationBuilder) {
    val choices = finiteChoices?.takeIf { it.hasGuards } ?: return
    val literals = IntArray(choices.size) { choices.literalAt(it) }
    builder.boolRow(literals, null, LinearOp.GE, 1L)
    val other = if (choices.operand == a) b else a
    for (index in 0 until choices.size) {
        val literal = choices.literalAt(index)
        val truth = builder.liveBool(Lit.variable(literal)) ?: continue
        if (!Lit.evaluate(literal, truth)) continue
        val operandColumn = builder.realColumn(choices.operand)
        val resultColumn = builder.realColumn(result)
        val otherColumn = builder.realColumn(other)
        if (operandColumn < 0 || resultColumn < 0 || otherColumn < 0) continue
        val value = choices.valueAt(index)
        builder.realRow(intArrayOf(operandColumn), doubleArrayOf(1.0), LinearOp.EQ, value,
            premiseLits = intArrayOf(literal))
        if (resultColumn == otherColumn) {
            val exact = BigFraction.ONE - requireNotNull(BigFraction.ofDouble(value))
            val coefficient = exact.toDouble()
            if (BigFraction.ofDouble(coefficient) != exact) continue
            builder.realRow(intArrayOf(resultColumn), doubleArrayOf(coefficient), LinearOp.EQ, 0.0,
                premiseLits = intArrayOf(literal))
        } else {
            builder.realRow(intArrayOf(resultColumn, otherColumn), doubleArrayOf(1.0, -value), LinearOp.EQ, 0.0,
                premiseLits = intArrayOf(literal))
        }
    }
}

internal fun ContinuousProduct.hasCompleteGuardedLpCompletion(): Boolean {
    val choices = finiteChoices?.takeIf { it.hasGuards } ?: return false
    val other = if (choices.operand == a) b else a
    if (other != result) return true
    return (0 until choices.size).all { index ->
        val coefficient = BigFraction.ONE - requireNotNull(BigFraction.ofDouble(choices.valueAt(index)))
        BigFraction.ofDouble(coefficient.toDouble()) == coefficient
    }
}
