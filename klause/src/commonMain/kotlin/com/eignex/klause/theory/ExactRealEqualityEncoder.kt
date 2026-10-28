package com.eignex.klause.theory

import com.eignex.klause.factor.arithmetic.ExactRealEquality
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction

internal fun exactRealEqualityEncoder(factor: ExactRealEquality, target: EncodingTarget): Encoder {
    if (target != EncodingTarget.POLYNOMIAL || factor.value.algebraic == null) {
        return Encoder(EncoderSupport.UNSUPPORTED)
    }
    return Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
        view as ExactRealEquality
        val value = requireNotNull(view.value.algebraic)
        val variable = encoding.real(view.id)
        fun constant(number: BigFraction) = PolynomialExpression.Constant.of(number.num, number.den)
        fun relation(op: PolynomialRelation, number: BigFraction) =
            PolynomialRealFormula.Atom(variable, op, constant(number))
        val rational = value.rational
        if (rational != null) encoding.polynomial(relation(PolynomialRelation.EQ, rational)) else {
            val polynomial = PolynomialExpression.Sum(value.definingCoefficients.mapIndexed { exponent, coefficient ->
                PolynomialExpression.Product(listOf(constant(coefficient), PolynomialExpression.Power(variable, exponent)))
            })
            encoding.polynomial(PolynomialRealFormula.And(listOf(
                PolynomialRealFormula.Atom(polynomial, PolynomialRelation.EQ, constant(BigFraction.ZERO)),
                relation(PolynomialRelation.GT, value.lowerBound),
                relation(PolynomialRelation.LT, value.upperBound),
            )))
        }
        null
    }
}
