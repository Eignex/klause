package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExactSearchRow

internal fun continuousProductEncoder(factor: ContinuousProduct, target: EncodingTarget): Encoder = when {
    target == EncodingTarget.POLYNOMIAL -> polynomialProduct
    target == EncodingTarget.LINEAR && factor.finiteChoices != null -> finiteLinearProduct
    else -> Encoder(EncoderSupport.UNSUPPORTED)
}

private val polynomialProduct = Encoder(EncoderSupport.DIRECT) { view, encoding ->
    view as ContinuousProduct
    encoding.polynomial(PolynomialRealFormula.Atom(
        PolynomialExpression.Product(listOf(encoding.real(SourceRealId(view.a)),
            encoding.real(SourceRealId(view.b)))), PolynomialRelation.EQ,
        encoding.real(SourceRealId(view.result))))
    view.finiteChoices?.let { choices ->
        val alternatives = ArrayList<PolynomialRealFormula>()
        for (index in 0 until choices.size) {
            if (encoding.cancellation()) return@Encoder EncoderDecline.RESOURCE
            encoding.cancellation.charge(1)
            val value = requireNotNull(BigFraction.ofDouble(choices.valueAt(index)))
            val member = PolynomialRealFormula.Atom(encoding.real(SourceRealId(choices.operand)),
                PolynomialRelation.EQ, PolynomialExpression.Constant.of(value.num, value.den))
            alternatives += member
            if (choices.hasGuards) {
                val literal = choices.literalAt(index)
                val variable = PolynomialRealFormula.BooleanVariable(SourceBoolId(Lit.variable(literal)))
                val guard = if (Lit.isPositive(literal)) variable else PolynomialRealFormula.Not(variable)
                encoding.polynomial(PolynomialRealFormula.And(listOf(
                    PolynomialRealFormula.Or(listOf(PolynomialRealFormula.Not(guard), member)),
                    PolynomialRealFormula.Or(listOf(guard, PolynomialRealFormula.Not(member))),
                )))
            }
        }
        encoding.polynomial(PolynomialRealFormula.Or(alternatives))
    }
    null
}

private val finiteLinearProduct = Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
    view as ContinuousProduct
    val choices = checkNotNull(view.finiteChoices)
    val other = if (choices.operand == view.a) view.b else view.a
    val alternatives = ArrayList<LinearEncodingFormula>()
    for (index in 0 until choices.size) {
        if (encoding.cancellation()) return@Encoder EncoderDecline.RESOURCE
        encoding.cancellation.charge(2)
        val value = requireNotNull(BigFraction.ofDouble(choices.valueAt(index)))
        val selected = ExactSearchRow.of(intArrayOf(Term.ofRealVar(choices.operand)), listOf(BigFraction.ONE),
            LinearOp.EQ, value)
        val product = ExactSearchRow.of(intArrayOf(Term.ofRealVar(view.result), Term.ofRealVar(other)),
            listOf(BigFraction.ONE, value.negated()), LinearOp.EQ, BigFraction.ZERO)
        alternatives += LinearEncodingFormula.And(listOf(LinearEncodingFormula.Relation(selected.linearForm),
            LinearEncodingFormula.Relation(product.linearForm)))
        if (choices.hasGuards) {
            fun guard(truth: Boolean): LinearEncodingFormula = LinearEncodingFormula.Relation(ExactSearchRow.of(
                intArrayOf(Term.ofLit(choices.literalAt(index))), listOf(BigFraction.ONE), LinearOp.EQ,
                if (truth) BigFraction.ONE else BigFraction.ZERO).linearForm)
            val absent = ExactSearchRow.of(intArrayOf(Term.ofRealVar(choices.operand)), listOf(BigFraction.ONE),
                LinearOp.NE, value)
            encoding.linearFormula(LinearEncodingFormula.And(listOf(
                LinearEncodingFormula.Or(listOf(guard(false), LinearEncodingFormula.Relation(selected.linearForm))),
                LinearEncodingFormula.Or(listOf(guard(true), LinearEncodingFormula.Relation(absent.linearForm))),
            )))
        }
    }
    encoding.linearFormula(LinearEncodingFormula.Or(alternatives))
    null
}
