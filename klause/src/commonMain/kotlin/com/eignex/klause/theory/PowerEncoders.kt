package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.integerPowerOrNull
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.ExactSearchRow
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal object PowerEncoders {
    private val unsupported = Encoder(EncoderSupport.UNSUPPORTED)
    private val polynomial = Encoder(EncoderSupport.DIRECT) { factor, encoding ->
        factor as Power
        encoding.polynomial(PolynomialRealFormula.Atom(
            PolynomialExpression.Power(encoding.coordinate(factor.base), factor.exponent),
            PolynomialRelation.EQ, encoding.coordinate(factor.result),
        ))
        null
    }
    private val affine = Encoder(EncoderSupport.EQUIVALENT) { factor, encoding ->
        factor as Power
        val terms = if (factor.exponent == 0) intArrayOf(factor.result.term()) else
            intArrayOf(factor.result.term(), factor.base.term())
        encoding.linear(ExactSearchRow.of(terms,
            if (factor.exponent == 0) listOf(BigFraction.ONE) else listOf(BigFraction.ONE, BigFraction.MINUS_ONE),
            LinearOp.EQ, if (factor.exponent == 0) BigFraction.ONE else BigFraction.ZERO).linearForm)
        null
    }

    fun select(factor: Power, target: EncodingTarget): Encoder = when {
        target == EncodingTarget.POLYNOMIAL -> polynomial
        target == EncodingTarget.LINEAR && factor.exponent <= 1 -> affine
        target == EncodingTarget.LINEAR && factor.base is Power.Coordinate.Integer -> Encoder(
            EncoderSupport.CONDITIONED, listOf(factor.base.id),
        ) { view, encoding ->
            view as Power
            val base = encoding.fixedInteger((view.base as Power.Coordinate.Integer).id)
                ?: return@Encoder EncoderDecline.CONDITIONS
            if (!base.fitsLong()) return@Encoder EncoderDecline.UNSUPPORTED
            val value = integerPowerOrNull(base.toLongExact(), view.exponent)
                ?: return@Encoder EncoderDecline.UNSUPPORTED
            encoding.linear(ExactSearchRow.of(intArrayOf(view.result.term()), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ofLong(value)).linearForm)
            null
        }
        else -> unsupported
    }

    private fun TheoryEncoding.coordinate(coordinate: Power.Coordinate): PolynomialExpression = when (coordinate) {
        is Power.Coordinate.Integer -> integer(coordinate.id)
        is Power.Coordinate.Real -> real(coordinate.id)
    }

    private fun Power.Coordinate.term(): Int = when (this) {
        is Power.Coordinate.Integer -> Term.ofIntVar(id.index)
        is Power.Coordinate.Real -> Term.ofRealVar(id.index)
    }
}
