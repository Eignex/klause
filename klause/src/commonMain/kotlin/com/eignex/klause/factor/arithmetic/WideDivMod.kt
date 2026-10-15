package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.times

internal class DivModExpression(coefficients: Map<Int, BigInt>, val constant: BigInt) {
    val coefficients = coefficients.toMap()

    fun evaluate(value: (Int) -> Long): BigInt = coefficients.entries.fold(constant) { sum, (variable, coefficient) ->
        sum + coefficient * bigIntOf(value(variable))
    }

    fun remap(mapping: VarRemap): DivModExpression {
        val terms = LinkedHashMap<Int, BigInt>()
        for ((variable, coefficient) in coefficients) {
            val mapped = mapping.int(variable)
            terms[mapped] = (terms[mapped] ?: BIG_ZERO) + coefficient
        }
        return DivModExpression(terms, constant)
    }
}

internal class WideDivMod(
    val dividend: DivModExpression,
    val divisor: BigInt,
    val quotient: DivModExpression,
    val remainder: DivModExpression,
) : Factor {
    val convention = DivisionConvention.EUCLIDEAN

    override val variables = IntVars((dividend.coefficients.keys + quotient.coefficients.keys +
        remainder.coefficients.keys).distinct().toIntArray())

    override fun remap(mapping: VarRemap): Factor = WideDivMod(dividend.remap(mapping), divisor,
        quotient.remap(mapping), remainder.remap(mapping))

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.WIDE_DIV_MOD) {
        fun words(value: BigInt) = ints(value.toString().map { it.code }.toIntArray())
        words(divisor)
        for (expression in listOf(dividend, quotient, remainder)) {
            words(expression.constant)
            int(expression.coefficients.size)
            for ((variable, coefficient) in expression.coefficients.entries.sortedBy { it.key }) {
                int(variable)
                words(coefficient)
            }
        }
    }
}
