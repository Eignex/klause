package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.GridArrayMinMax
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.RealChoice
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.SelectionEncodingInterrupted
import com.eignex.klause.lowering.elementEncoding
import com.eignex.klause.lowering.gridMinMaxEncoding
import com.eignex.klause.lowering.minMaxEncoding
import com.eignex.klause.lowering.polynomialRow
import com.eignex.klause.lowering.realChoiceEncoding
import com.eignex.klause.lowering.realElementEncoding
import com.eignex.klause.lowering.realMinMaxEncoding

internal val selectionEncoder = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
    try {
        if (encoding.target == EncodingTarget.POLYNOMIAL) {
            val row = { row: LinearRow -> polynomialRow(row,
                { encoding.integer(SourceIntId(it)) }, { encoding.real(SourceRealId(it)) }, encoding::conditional) }
            val formula = when (factor) {
                is ArrayMinMax -> minMaxEncoding(factor, encoding.cancellation, row,
                    { PolynomialRealFormula.And(it) }, { PolynomialRealFormula.Or(it) })
                is GridArrayMinMax -> gridMinMaxEncoding(factor, encoding.cancellation, row,
                    { PolynomialRealFormula.And(it) }, { PolynomialRealFormula.Or(it) })
                is Element -> elementEncoding(factor, encoding.cancellation, row,
                    { PolynomialRealFormula.And(it) }, { PolynomialRealFormula.Or(it) }, affineBinary = true)
                is RealElement -> realElementEncoding(factor, encoding.cancellation, row,
                    { PolynomialRealFormula.And(it) }, { PolynomialRealFormula.Or(it) })
                is RealChoice -> realChoiceEncoding(factor, encoding.cancellation, row,
                    { PolynomialRealFormula.And(it) }, { PolynomialRealFormula.Or(it) })
                is RealArrayMinMax -> realMinMaxEncoding(factor, encoding.cancellation, row,
                    { PolynomialRealFormula.And(it) }, { PolynomialRealFormula.Or(it) })
                else -> error("selection encoder requires a selection factor")
            }
            encoding.polynomial(formula)
        } else {
            val row = { row: LinearRow -> LinearEncodingFormula.Relation(LinearForm.Conjunction(listOf(row))) }
            val formula = when (factor) {
                is ArrayMinMax -> minMaxEncoding<LinearEncodingFormula>(factor, encoding.cancellation, row,
                    { LinearEncodingFormula.And(it) }, { LinearEncodingFormula.Or(it) })
                is GridArrayMinMax -> gridMinMaxEncoding<LinearEncodingFormula>(factor, encoding.cancellation, row,
                    { LinearEncodingFormula.And(it) }, { LinearEncodingFormula.Or(it) })
                is Element -> elementEncoding<LinearEncodingFormula>(factor, encoding.cancellation, row,
                    { LinearEncodingFormula.And(it) }, { LinearEncodingFormula.Or(it) },
                    affineBinary = encoding.target != EncodingTarget.DIFFERENCE)
                is RealElement -> realElementEncoding<LinearEncodingFormula>(factor, encoding.cancellation, row,
                    { LinearEncodingFormula.And(it) }, { LinearEncodingFormula.Or(it) })
                is RealChoice -> realChoiceEncoding<LinearEncodingFormula>(factor, encoding.cancellation, row,
                    { LinearEncodingFormula.And(it) }, { LinearEncodingFormula.Or(it) })
                is RealArrayMinMax -> realMinMaxEncoding<LinearEncodingFormula>(factor, encoding.cancellation, row,
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
