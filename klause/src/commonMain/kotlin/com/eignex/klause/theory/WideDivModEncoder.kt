package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.polynomialRow
import com.eignex.klause.lowering.wideDivModRows

internal fun wideDivModEncoder(target: EncodingTarget): Encoder = when (target) {
    EncodingTarget.LINEAR -> Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
        for (row in wideDivModRows(view as WideDivMod)) encoding.linear(row.linearForm)
        null
    }
    EncodingTarget.POLYNOMIAL -> Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
        encoding.polynomial(PolynomialRealFormula.And(wideDivModRows(view as WideDivMod).map { row ->
            polynomialRow(row, { encoding.integer(SourceIntId(it)) }, { encoding.real(SourceRealId(it)) },
                encoding::conditional)
        }))
        null
    }
    else -> Encoder(EncoderSupport.UNSUPPORTED)
}
