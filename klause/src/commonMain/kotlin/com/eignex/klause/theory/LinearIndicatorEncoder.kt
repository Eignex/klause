package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.lowering.polynomialRow
import com.eignex.klause.simplex.exact.BigFraction

internal object LinearIndicatorEncoder {
    val linear = Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
        view as LinearIndicator
        val inactive = LinearEncodingFormula.Comparison(EncodingAffineExpression(listOf(
            EncodingLinearTerm.source(Term.ofIntVar(view.indicator), BigFraction.ONE),
        )), LinearOp.NE, BigFraction.ofLong(if (view.whenOne) 1 else 0))
        encoding.linearFormula(LinearEncodingFormula.Or(listOf(inactive,
            LinearEncodingFormula.Relation(view.row.linearForm))))
        null
    }

    val polynomial = Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
        view as LinearIndicator
        val inactive = PolynomialRealFormula.Atom(encoding.integer(SourceIntId(view.indicator)),
            PolynomialRelation.NE, PolynomialRealLowering.constant(BigFraction.ofLong(if (view.whenOne) 1 else 0)))
        val enabled = polynomialRow(view.row,
            { id -> encoding.integer(SourceIntId(id)) },
            { id -> encoding.real(SourceRealId(id)) }, encoding::conditional)
        encoding.polynomial(PolynomialRealFormula.Or(listOf(inactive, enabled)))
        null
    }
}
