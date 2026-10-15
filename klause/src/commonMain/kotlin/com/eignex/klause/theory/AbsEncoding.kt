package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.absoluteValueRows
import com.eignex.klause.lowering.polynomialRow
import com.eignex.klause.lowering.realAbsoluteValueRows

internal object AbsEncoding {
    val linear = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
        factor as Abs
        for (form in absoluteValueRows(factor.operand, factor.result)) encoding.linear(form)
        null
    }

    val realLinear = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
        factor as RealAbs
        for (form in realAbsoluteValueRows(factor.operand, factor.result)) encoding.linear(form)
        null
    }

    val polynomial = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
        factor as Abs
        installPolynomial(absoluteValueRows(factor.operand, factor.result), encoding)
        null
    }

    val realPolynomial = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
        factor as RealAbs
        installPolynomial(realAbsoluteValueRows(factor.operand, factor.result), encoding)
        null
    }

    private fun installPolynomial(forms: List<LinearForm>, encoding: TheoryEncoding) {
        val formulas = forms.map { form ->
            val rows = form.rows.map {
                polynomialRow(
                    it,
                    { id -> encoding.integer(SourceIntId(id)) },
                    { id -> encoding.real(SourceRealId(id)) },
                    encoding::conditional,
                )
            }
            if (form is LinearForm.Disjunction) PolynomialRealFormula.Or(rows) else PolynomialRealFormula.And(rows)
        }
        encoding.polynomial(PolynomialRealFormula.And(formulas))
    }
}
