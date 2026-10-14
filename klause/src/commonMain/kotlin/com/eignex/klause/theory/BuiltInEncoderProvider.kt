package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.polynomialRow
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.hasCompleteDifferenceCoverage
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.theory.qflra.exactTheoryOwnable
import com.eignex.klause.util.BIG_ONE

/** Built-in complete relations with the existing exact, guarded and finite-conditioned semantics. */
object BuiltInEncoderProvider : EncoderProvider {
    override fun select(factor: Factor, target: EncodingTarget): Encoder = when {
        target !in EncodingTarget.entries -> Encoder(EncoderSupport.UNSUPPORTED)
        target == EncodingTarget.POLYNOMIAL && factor is ContinuousProduct -> Encoder(EncoderSupport.DIRECT) {
                view, encoding ->
            view as ContinuousProduct
            encoding.polynomial(PolynomialRealFormula.Atom(
                PolynomialExpression.Product(listOf(encoding.real(SourceRealId(view.a)),
                    encoding.real(SourceRealId(view.b)))), PolynomialRelation.EQ,
                encoding.real(SourceRealId(view.result))))
            null
        }
        target == EncodingTarget.POLYNOMIAL && factor is RealProduct -> Encoder(EncoderSupport.DIRECT) {
                view, encoding ->
            view as RealProduct
            encoding.polynomial(PolynomialRealFormula.Atom(
                PolynomialExpression.Product(listOf(encoding.integer(SourceIntId(view.intOperand)),
                    encoding.real(SourceRealId(view.realOperand)))), PolynomialRelation.EQ,
                encoding.real(SourceRealId(view.result))))
            null
        }
        target == EncodingTarget.LINEAR && factor is RealProduct -> Encoder(
            EncoderSupport.CONDITIONED, listOf(SourceIntId(factor.intOperand)),
        ) { view, encoding ->
            view as RealProduct
            val value = encoding.fixedInteger(SourceIntId(view.intOperand)) ?: return@Encoder EncoderDecline.CONDITIONS
            encoding.linear(ExactSearchRow.of(
                intArrayOf(Term.ofRealVar(view.result), Term.ofRealVar(view.realOperand)),
                listOf(BigFraction.ONE, BigFraction.of(value, BIG_ONE).negated()), LinearOp.EQ, BigFraction.ZERO,
            ).linearForm)
            null
        }
        factor.exactTheoryOwnable && (target != EncodingTarget.DIFFERENCE ||
            hasCompleteDifferenceCoverage(arrayOf(factor))) -> Encoder(
            if (target == EncodingTarget.POLYNOMIAL || factor !is LinearRow) EncoderSupport.EQUIVALENT
            else EncoderSupport.DIRECT,
        ) { view, encoding ->
            val form = checkNotNull(view.linearForm)
            if (target == EncodingTarget.POLYNOMIAL) {
                val rows = form.rows.map { polynomialRow(it,
                    { id -> encoding.integer(SourceIntId(id)) },
                    { id -> encoding.real(SourceRealId(id)) }, encoding::conditional) }
                encoding.polynomial(if (form is LinearForm.Disjunction) PolynomialRealFormula.Or(rows)
                    else PolynomialRealFormula.And(rows))
            } else encoding.linear(form)
            null
        }
        factor.linearForm is LinearForm.Relaxation -> Encoder(EncoderSupport.RELAXATION_ONLY)
        else -> Encoder(EncoderSupport.UNSUPPORTED)
    }
}
