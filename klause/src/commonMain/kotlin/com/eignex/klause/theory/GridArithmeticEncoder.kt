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
            encoding.linearFormula(LinearEncodingFormula.Or(alternatives.map { rows ->
                LinearEncodingFormula.Relation(LinearForm.Conjunction(rows))
            }))
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
