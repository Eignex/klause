package com.eignex.klause.factor.arithmetic

import com.eignex.klause.arithmetic.real.ExactRealExpression
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.RealVars
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.StructuralKeyBuilder
import com.eignex.klause.ir.VarRemap

/** Equality between a native real column and an owned exact scalar or closed analytic expression. */
class ExactRealEquality(
    /** Original real column. */
    val id: SourceRealId,
    /** Authoritative value; numerical approximations cannot constrain this equality. */
    val value: ExactRealValue,
) : Factor {
    override val variables = RealVars(intArrayOf(id.index))

    override fun remap(mapping: VarRemap): Factor = ExactRealEquality(SourceRealId(mapping.real(id.index)), value)

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.EXACT_REAL_EQUALITY) {
        int(id.index)
        expression(value.expression)
    }
}

private fun StructuralKeyBuilder.expression(node: ExactRealExpression) {
    fun text(value: String) = ints(value.map { it.code }.toIntArray())
    when (node) {
        is ExactRealExpression.Algebraic -> {
            int(0)
            val rational = node.value.rational
            bool(rational != null)
            if (rational != null) text(rational.toString()) else {
                int(node.value.rootIndex)
                int(node.value.definingCoefficients.size)
                node.value.definingCoefficients.forEach { text(it.toString()) }
            }
        }
        ExactRealExpression.Pi -> int(1)
        is ExactRealExpression.Function -> { int(2); enum(node.function); expression(node.argument) }
        is ExactRealExpression.Sum -> { int(3); int(node.operands.size); node.operands.forEach(::expression) }
        is ExactRealExpression.Product -> { int(4); int(node.operands.size); node.operands.forEach(::expression) }
        is ExactRealExpression.Power -> { int(5); int(node.exponent); expression(node.base) }
    }
}
