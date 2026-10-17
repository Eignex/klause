package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExactSearchRow

internal fun realFiniteDomainEncoder(target: EncodingTarget): Encoder = when (target) {
    EncodingTarget.LINEAR -> finiteLinearDomain
    EncodingTarget.POLYNOMIAL -> finitePolynomialDomain
    else -> Encoder(EncoderSupport.UNSUPPORTED)
}

private val finiteLinearDomain = Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
    view as RealFiniteDomain
    val alternatives = ArrayList<ExactSearchRow>()
    for (index in 0 until view.size) {
        encoding.cancellation.charge(1)
        if (encoding.cancellation()) return@Encoder EncoderDecline.RESOURCE
        alternatives += ExactSearchRow.of(intArrayOf(Term.ofRealVar(view.variable)), listOf(BigFraction.ONE),
            LinearOp.EQ, requireNotNull(BigFraction.ofDouble(view.valueAt(index))))
    }
    if (encoding.cancellation()) return@Encoder EncoderDecline.RESOURCE
    encoding.linear(LinearForm.Disjunction(alternatives))
    null
}

private val finitePolynomialDomain = Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
    view as RealFiniteDomain
    val alternatives = ArrayList<PolynomialRealFormula>()
    for (index in 0 until view.size) {
        encoding.cancellation.charge(1)
        if (encoding.cancellation()) return@Encoder EncoderDecline.RESOURCE
        val value = requireNotNull(BigFraction.ofDouble(view.valueAt(index)))
        alternatives += PolynomialRealFormula.Atom(encoding.real(SourceRealId(view.variable)),
            PolynomialRelation.EQ, PolynomialExpression.Constant.of(value.num, value.den))
    }
    if (encoding.cancellation()) return@Encoder EncoderDecline.RESOURCE
    encoding.polynomial(PolynomialRealFormula.Or(alternatives))
    null
}
