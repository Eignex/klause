package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.SelectionEncodingInterrupted
import com.eignex.klause.lowering.elementEncoding
import com.eignex.klause.lowering.minMaxEncoding
import com.eignex.klause.lowering.polynomialRow

internal val selectionEncoder = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
    try {
        if (encoding.target == EncodingTarget.POLYNOMIAL) {
            val row = { row: LinearRow -> polynomialRow(row,
                { encoding.integer(SourceIntId(it)) }, { encoding.real(SourceRealId(it)) }, encoding::conditional) }
            val formula = when (factor) {
                is ArrayMinMax -> minMaxEncoding(factor, encoding.cancellation, row,
                    { PolynomialRealFormula.And(it) }, { PolynomialRealFormula.Or(it) })
                is Element -> elementEncoding(factor, encoding.cancellation, row,
                    { PolynomialRealFormula.And(it) }, { PolynomialRealFormula.Or(it) })
                else -> error("selection encoder requires a selection factor")
            }
            encoding.polynomial(formula)
        } else {
            val row = { row: LinearRow -> LinearEncodingFormula.Relation(LinearForm.Conjunction(listOf(row))) }
            val formula = when (factor) {
                is ArrayMinMax -> minMaxEncoding<LinearEncodingFormula>(factor, encoding.cancellation, row,
                    { LinearEncodingFormula.And(it) }, { LinearEncodingFormula.Or(it) })
                is Element -> elementEncoding<LinearEncodingFormula>(factor, encoding.cancellation, row,
                    { LinearEncodingFormula.And(it) }, { LinearEncodingFormula.Or(it) })
                else -> error("selection encoder requires a selection factor")
            }
            encoding.linearFormula(formula)
        }
        null
    } catch (_: SelectionEncodingInterrupted) {
        EncoderDecline.RESOURCE
    }
}
