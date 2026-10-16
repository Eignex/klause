package com.eignex.klause.lowering

import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.PolynomialDistinct
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.EncodingAffineExpression
import com.eignex.klause.theory.EncodingLinearTerm
import com.eignex.klause.theory.LinearEncodingFormula
import com.eignex.klause.util.Cancellation

internal fun distinctLinearFormula(factor: Distinct, cancellation: Cancellation): LinearEncodingFormula? {
    if (cancellation()) return null
    val operands = factor.operands
    val differences = ArrayList<LinearEncodingFormula>()
    val collisions = ArrayList<LinearEncodingFormula>()
    for (first in operands.indices) {
        for (second in first + 1 until operands.size) {
            val left = operands[first]
            val right = operands[second]
            cancellation.charge(1L + left.size + right.size)
            if (cancellation()) return null
            val terms = left.references.zip(left.coefficients) { reference, coefficient ->
                EncodingLinearTerm.source(reference, coefficient)
            } + right.references.zip(right.coefficients) { reference, coefficient ->
                EncodingLinearTerm.source(reference, coefficient.negated())
            }
            val expression = EncodingAffineExpression(terms, left.constant - right.constant)
            differences += LinearEncodingFormula.Comparison(expression, LinearOp.NE, BigFraction.ZERO)
            if (factor.activator != LinearRow.ALWAYS) {
                collisions += LinearEncodingFormula.Comparison(expression, LinearOp.EQ, BigFraction.ZERO)
            }
        }
    }
    val distinct = LinearEncodingFormula.And(differences)
    if (factor.activator == LinearRow.ALWAYS) return distinct
    val activator = EncodingAffineExpression(listOf(
        EncodingLinearTerm.source(Term.ofLit(Lit.make(factor.activator, true)), BigFraction.ONE),
    ))
    val positive = LinearEncodingFormula.Comparison(activator, LinearOp.EQ, BigFraction.ONE)
    val negative = LinearEncodingFormula.Comparison(activator, LinearOp.EQ, BigFraction.ZERO)
    return LinearEncodingFormula.And(listOf(
        LinearEncodingFormula.Or(listOf(negative, distinct)),
        LinearEncodingFormula.Or(listOf(positive, LinearEncodingFormula.Or(collisions))),
    ))
}

internal fun distinctPolynomialFormula(
    factor: Distinct,
    integer: (SourceIntId) -> PolynomialExpression,
    real: (SourceRealId) -> PolynomialExpression,
    conditional: (PolynomialRealFormula, PolynomialExpression, PolynomialExpression) -> PolynomialExpression,
    cancellation: Cancellation,
): PolynomialRealFormula? {
    if (cancellation()) return null
    val values = ArrayList<PolynomialExpression>()
    for (operand in factor.operands) {
        val terms = arrayListOf<PolynomialExpression>(PolynomialRealLowering.constant(operand.constant))
        for ((reference, coefficient) in operand.references.zip(operand.coefficients)) {
            cancellation.charge(1)
            if (cancellation()) return null
            val coordinate = when {
                Term.isInt(reference) -> integer(SourceIntId(Term.intVar(reference)))
                Term.isReal(reference) -> real(SourceRealId(Term.realVar(reference)))
                else -> conditional(
                    PolynomialRealFormula.BooleanVariable(SourceBoolId(Lit.variable(Term.lit(reference)))),
                    PolynomialRealLowering.constant(BigFraction.ONE), PolynomialRealLowering.constant(BigFraction.ZERO),
                )
            }
            terms += PolynomialExpression.Product(listOf(PolynomialRealLowering.constant(coefficient), coordinate))
        }
        values += PolynomialExpression.Sum(terms)
    }
    if (cancellation()) return null
    val formula = PolynomialDistinct(values)
    return if (factor.activator == LinearRow.ALWAYS) formula else PolynomialRealLowering.equivalent(
        PolynomialRealFormula.BooleanVariable(SourceBoolId(factor.activator)), formula,
    )
}
