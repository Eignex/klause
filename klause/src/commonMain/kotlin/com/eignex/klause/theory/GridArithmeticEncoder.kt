package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.GridArithmetic
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.GRID_ARITHMETIC_ENCODING_LIMIT
import com.eignex.klause.lowering.encodingSize
import com.eignex.klause.lowering.gridArithmeticTuples
import com.eignex.klause.lowering.polynomialRow

internal fun gridArithmeticEncoder(factor: GridArithmetic, target: EncodingTarget): Encoder {
    if (target !in setOf(EncodingTarget.LINEAR, EncodingTarget.POLYNOMIAL) ||
        factor.encodingSize() > GRID_ARITHMETIC_ENCODING_LIMIT) return Encoder(EncoderSupport.UNSUPPORTED)
    return Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
        view as GridArithmetic
        val tuples = gridArithmeticTuples(view, encoding.cancellation) ?: return@Encoder EncoderDecline.RESOURCE
        val alternatives = ArrayList<List<Linear>>()
        for (tuple in tuples) {
            if (encoding.cancellation()) return@Encoder EncoderDecline.RESOURCE
            encoding.cancellation.charge(tuple.size.toLong())
            alternatives += tuple.mapIndexed { index, value ->
                Linear(longArrayOf(1L), intArrayOf(view.intVars[index]), LinearOp.EQ, value)
            }
        }
        if (target == EncodingTarget.LINEAR) {
            val inputs = view.inputs.map { it.variable }.distinct()
            if (inputs.size == 1) {
                val input = inputs.single()
                val position = view.intVars.indexOf(input)
                val cover = alternatives.map { rows -> rows[position] }
                encoding.linear(LinearForm.Disjunction(cover))
                for (tuple in tuples) {
                    if (encoding.cancellation()) return@Encoder EncoderDecline.RESOURCE
                    encoding.cancellation.charge(1)
                    val inputValue = tuple[position]
                    val outputPosition = view.intVars.indexOf(view.output.variable)
                    encoding.linear(LinearForm.Disjunction(listOf(
                        Linear(longArrayOf(1L), intArrayOf(input), LinearOp.NE, inputValue),
                        Linear(longArrayOf(1L), intArrayOf(view.output.variable), LinearOp.EQ, tuple[outputPosition]),
                    )))
                }
            } else {
                val first = inputs[0]
                val second = inputs[1]
                val firstPosition = view.intVars.indexOf(first)
                val secondPosition = view.intVars.indexOf(second)
                val groups = tuples.groupBy { it[firstPosition] }
                encoding.linear(LinearForm.Disjunction(groups.keys.map { value ->
                    Linear(longArrayOf(1L), intArrayOf(first), LinearOp.EQ, value)
                }))
                for ((value, group) in groups) {
                    if (encoding.cancellation()) return@Encoder EncoderDecline.RESOURCE
                    encoding.cancellation.charge(group.size.toLong())
                    val absent = Linear(longArrayOf(1L), intArrayOf(first), LinearOp.NE, value)
                    val cover = group.map { it[secondPosition] }.distinct().map {
                        Linear(longArrayOf(1L), intArrayOf(second), LinearOp.EQ, it)
                    }
                    encoding.linear(LinearForm.Disjunction(listOf(absent) + cover))
                    for (tuple in group) {
                        if (encoding.cancellation()) return@Encoder EncoderDecline.RESOURCE
                        encoding.cancellation.charge(1)
                        val outputPosition = view.intVars.indexOf(view.output.variable)
                        encoding.linear(LinearForm.Disjunction(listOf(absent,
                            Linear(longArrayOf(1L), intArrayOf(second), LinearOp.NE, tuple[secondPosition]),
                            Linear(longArrayOf(1L), intArrayOf(view.output.variable),
                                LinearOp.EQ, tuple[outputPosition]),
                        )))
                    }
                }
            }
        } else {
            val formula = PolynomialRealFormula.Or(alternatives.map { rows ->
                PolynomialRealFormula.And(rows.map { row -> polynomialRow(row,
                    { id -> encoding.integer(SourceIntId(id)) }, { id -> encoding.real(SourceRealId(id)) },
                    encoding::conditional) })
            })
            encoding.polynomial(formula)
        }
        null
    }
}
