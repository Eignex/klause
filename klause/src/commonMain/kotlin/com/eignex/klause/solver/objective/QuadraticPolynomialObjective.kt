package com.eignex.klause.solver.objective

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample

internal fun QuadraticObjectiveSpec.polynomialFor(finite: Sample): PolynomialExpression {
    fun coordinate(variable: QuadraticVariable): PolynomialExpression = when (variable) {
        is QuadraticVariable.Bool -> PolynomialExpression.Constant.of(
            if (finite.boolValue(variable.id.index)) BigFraction.ONE.num else BigFraction.ZERO.num,
        )
        is QuadraticVariable.Int -> PolynomialExpression.Constant.of(finite.exactIntValue(variable.id.index))
        is QuadraticVariable.Real -> PolynomialExpression.Variable(variable.id)
    }
    return PolynomialExpression.Sum(listOf(PolynomialExpression.Constant.of(constant.numerator, constant.denominator)) +
        terms.map { term ->
            PolynomialExpression.Product(buildList {
                add(PolynomialExpression.Constant.of(term.coefficient.numerator, term.coefficient.denominator))
                add(coordinate(term.first))
                term.second?.let { add(coordinate(it)) }
            })
        })
}

internal fun QuadraticObjectiveSpec.evaluateExact(sample: Sample, arithmetic: AlgebraicArithmetic): AlgebraicReal =
    evaluateExact(arithmetic) { variable ->
        when (variable) {
            is QuadraticVariable.Bool -> arithmetic.rational(
                if (sample.boolValue(variable.id.index)) BigFraction.ONE else BigFraction.ZERO,
            )
            is QuadraticVariable.Int -> arithmetic.rational(
                BigFraction.of(sample.exactIntValue(variable.id.index), BigFraction.ONE.den),
            )
            is QuadraticVariable.Real -> sample.algebraicReals?.get(variable.id.index)
                ?: arithmetic.rational(requireNotNull(sample.exactReals?.get(variable.id.index)) {
                    "quadratic objective evaluation requires an exact real witness"
                })
        }
    }
