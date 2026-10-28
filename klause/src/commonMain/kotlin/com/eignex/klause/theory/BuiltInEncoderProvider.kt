package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.ExactRealEquality
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.GridAbs
import com.eignex.klause.factor.arithmetic.GridArithmetic
import com.eignex.klause.factor.arithmetic.GridArrayMinMax
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.polynomialRow
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.hasCompleteDifferenceCoverage
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.theory.qflra.exactTheoryOwnable
import com.eignex.klause.util.BIG_ONE

/** Built-in encoders for every factor kind, with exact, guarded and finite-conditioned semantics. */
object BuiltInEncoderProvider : EncoderProvider {
    private val unsupported = Encoder(EncoderSupport.UNSUPPORTED)
    private val relaxation = Encoder(EncoderSupport.RELAXATION_ONLY)
    private val nativeLinear = Encoder(EncoderSupport.DIRECT, installer = ::installLinear)
    private val equivalentLinear = Encoder(EncoderSupport.EQUIVALENT, installer = ::installLinear)
    private val polynomialClause = Encoder(EncoderSupport.DIRECT) { view, encoding ->
        view as Clause
        val literals = view.literals.map { literal ->
            encoding.cancellation.polynomialSourceCheckpoint(1)
            val variable = PolynomialRealFormula.BooleanVariable(SourceBoolId(Lit.variable(literal)))
            if (Lit.isPositive(literal)) variable else PolynomialRealFormula.Not(variable)
        }
        encoding.polynomial(PolynomialRealFormula.Or(literals))
        null
    }
    private val polynomialRows = Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
        val form = checkNotNull(view.linearForm)
        val rows = form.rows.map { polynomialRow(it,
            { id -> encoding.integer(SourceIntId(id)) },
            { id -> encoding.real(SourceRealId(id)) }, encoding::conditional) }
        encoding.polynomial(if (form is LinearForm.Disjunction) PolynomialRealFormula.Or(rows)
            else PolynomialRealFormula.And(rows))
        null
    }
    private val mixedProduct = Encoder(EncoderSupport.DIRECT) { view, encoding ->
        view as RealProduct
        encoding.polynomial(PolynomialRealFormula.Atom(
            PolynomialExpression.Product(listOf(encoding.integer(SourceIntId(view.intOperand)),
                encoding.real(SourceRealId(view.realOperand)))), PolynomialRelation.EQ,
            encoding.real(SourceRealId(view.result))))
        null
    }

    override fun select(factor: Factor, target: EncodingTarget): Encoder = when {
        target !in EncodingTarget.entries -> unsupported
        target == EncodingTarget.POLYNOMIAL && factor is Clause -> polynomialClause
        factor is AllDifferent -> when (target) {
            EncodingTarget.LINEAR -> AllDifferentEncoder.linear
            EncodingTarget.POLYNOMIAL -> AllDifferentEncoder.polynomial
            else -> unsupported
        }
        factor is Distinct -> when (target) {
            EncodingTarget.LINEAR -> DistinctEncoders.linear
            EncodingTarget.POLYNOMIAL -> DistinctEncoders.polynomial
            else -> unsupported
        }
        factor is LinearIndicator -> when (target) {
            EncodingTarget.LINEAR -> LinearIndicatorEncoder.linear
            EncodingTarget.POLYNOMIAL -> LinearIndicatorEncoder.polynomial
            else -> unsupported
        }
        factor is ArrayMinMax || factor is Element -> selectionEncoder
        factor is Abs && target == EncodingTarget.LINEAR -> AbsEncoding.linear
        factor is Abs && target == EncodingTarget.POLYNOMIAL -> AbsEncoding.polynomial
        factor is ExactRealEquality -> exactRealEqualityEncoder(factor, target)
        factor is Power -> PowerEncoders.select(factor, target)
        factor is GridArithmetic -> gridArithmeticEncoder(factor, target)
        factor is WideDivMod -> wideDivModEncoder(target)
        factor is DivMod -> divModEncoder(factor, target)
        factor is AffineAbs && target == EncodingTarget.LINEAR -> AbsEncoding.affineLinear
        factor is AffineAbs && target == EncodingTarget.POLYNOMIAL -> AbsEncoding.affinePolynomial
        factor is RealAbs && target == EncodingTarget.LINEAR -> AbsEncoding.realLinear
        factor is RealAbs && target == EncodingTarget.POLYNOMIAL -> AbsEncoding.realPolynomial
        factor is GridAbs && target == EncodingTarget.LINEAR -> AbsEncoding.gridLinear
        factor is GridAbs && target == EncodingTarget.POLYNOMIAL -> AbsEncoding.gridPolynomial
        factor is GridArrayMinMax && target != EncodingTarget.DIFFERENCE -> selectionEncoder
        factor is RealElement && target != EncodingTarget.DIFFERENCE -> selectionEncoder
        factor is RealArrayMinMax && target != EncodingTarget.DIFFERENCE -> selectionEncoder
        factor is ContinuousProduct -> continuousProductEncoder(factor, target)
        factor is RealFiniteDomain -> realFiniteDomainEncoder(target)
        target == EncodingTarget.POLYNOMIAL && factor is RealProduct -> mixedProduct
        target == EncodingTarget.LINEAR && factor is RealProduct -> conditionedProduct(factor)
        factor.exactTheoryOwnable && (target != EncodingTarget.DIFFERENCE ||
            (factor.variables.reals.isEmpty() && differenceSupported(factor))) -> when {
            target == EncodingTarget.POLYNOMIAL -> polynomialRows
            factor is LinearRow -> nativeLinear
            else -> equivalentLinear
        }
        factor.linearForm is LinearForm.Relaxation -> relaxation
        else -> unsupported
    }

    private fun conditionedProduct(factor: RealProduct): Encoder = Encoder(
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

    private fun installLinear(view: Factor, encoding: TheoryEncoding): EncoderDecline? {
        encoding.linear(checkNotNull(view.linearForm))
        return null
    }

    private fun differenceSupported(factor: Factor): Boolean {
        if (factor.intVars.isEmpty()) return true
        val form = factor.linearForm as? LinearForm.Conjunction ?: return false
        if (form.rows.any { row ->
                row.size > 2 || (row.isIntegerOnly && row.constants is IntegerConstants && row.size == 2 &&
                    row.coeff(0) != Long.MIN_VALUE && row.coeff(0) != -row.coeff(1))
            }) return false
        return hasCompleteDifferenceCoverage(arrayOf(factor))
    }
}
