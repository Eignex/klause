package com.eignex.klause.solver.objective

import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.simplex.exact.BigFraction

internal class QuadraticOpenObjective(
    val formula: PolynomialRealFormula,
    val expression: PolynomialExpression,
    val numericCount: Int,
)

internal fun QuadraticObjectiveSpec.openPolynomial(
    source: NonlinearIntegerProblem,
    step: () -> Unit = {},
): QuadraticOpenObjective {
    step()
    val lowering = PolynomialRealLowering(source.numericCount)
    val coordinates = HashMap<QuadraticVariable, PolynomialExpression>()
    fun coordinate(variable: QuadraticVariable): PolynomialExpression = coordinates.getOrPut(variable) {
        step()
        when (variable) {
            is QuadraticVariable.Bool -> {
                require(variable.id.index in 0 until source.boolCount) { "quadratic Boolean id out of range" }
                lowering.conditional(
                    PolynomialRealFormula.BooleanVariable(variable.id),
                    PolynomialRealLowering.constant(BigFraction.ONE),
                    PolynomialRealLowering.constant(BigFraction.ZERO),
                )
            }
            is QuadraticVariable.Int -> {
                require(variable.id.index in source.integerCoordinates.indices) { "quadratic integer id out of range" }
                PolynomialExpression.Variable(SourceRealId(source.integerCoordinates[variable.id.index]))
            }
            is QuadraticVariable.Real -> {
                require(variable.id.index in source.realCoordinates.indices) { "quadratic real id out of range" }
                PolynomialExpression.Variable(SourceRealId(source.realCoordinates[variable.id.index]))
            }
        }
    }
    val expression = PolynomialExpression.Sum(
        listOf(PolynomialExpression.Constant.of(constant.numerator, constant.denominator)) + terms.map { term ->
            step()
            PolynomialExpression.Product(buildList {
                add(PolynomialExpression.Constant.of(term.coefficient.numerator, term.coefficient.denominator))
                add(coordinate(term.first))
                term.second?.let { add(coordinate(it)) }
            })
        },
    )
    step()
    return QuadraticOpenObjective(lowering.formula(listOf(source.formula)), expression, lowering.realCount)
}
