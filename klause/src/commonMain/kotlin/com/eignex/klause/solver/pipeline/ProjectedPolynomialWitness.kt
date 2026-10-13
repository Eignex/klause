package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.theory.qfnra.solveQfNra
import com.eignex.klause.util.Cancellation

internal fun checkProjectedPolynomialWitness(
    source: PolynomialRealProblem,
    point: Sample,
    cancellation: Cancellation,
    limits: PolynomialLimits = PolynomialLimits(),
): SourceEvidenceCheck = try {
    val arithmetic = PolynomialArithmetic(cancellation, limits)
    arithmetic.step()
    if (point.numBoolVars != source.sourceBoolCount || point.numIntVars != 0 ||
        point.numRealVars != source.sourceRealCount
    ) {
        SourceEvidenceCheck.Rejected("polynomial witness has different source coordinates")
    } else {
        projectedPolynomialPoint(source, point, cancellation, arithmetic, limits)
    }
} catch (_: PolynomialInterrupted) {
    SourceEvidenceCheck.Unavailable("projected polynomial witness checking interrupted")
} catch (_: PolynomialResourceLimit) {
    SourceEvidenceCheck.Unavailable("projected polynomial witness checking exceeded representation limits")
}

private fun projectedPolynomialPoint(
    source: PolynomialRealProblem,
    point: Sample,
    cancellation: Cancellation,
    arithmetic: PolynomialArithmetic,
    limits: PolynomialLimits,
): SourceEvidenceCheck {
    val algebraic = AlgebraicArithmetic(arithmetic)
    val values = point.algebraicReals ?: point.exactReals?.map(algebraic::rational)
        ?: if (source.sourceRealCount == 0) emptyList() else {
            return SourceEvidenceCheck.Unavailable("polynomial witness has no exact source coordinates")
        }
    return checkProjectedPolynomialFormula(source.formula, source.totalBoolCount, source.totalRealCount,
        point.bools, values.withIndex().associate { it.index to it.value }, cancellation, arithmetic, limits)
}

internal fun checkProjectedPolynomialFormula(
    formula: PolynomialRealFormula,
    boolCount: Int,
    realCount: Int,
    booleans: BooleanArray,
    values: Map<Int, AlgebraicReal>,
    cancellation: Cancellation,
    arithmetic: PolynomialArithmetic,
    limits: PolynomialLimits,
): SourceEvidenceCheck {
    val algebraic = AlgebraicArithmetic(arithmetic)
    val fixed = mutableMapOf<Int, BigFraction>()
    val terms = mutableListOf(formula)
    for ((id, value) in values) {
        arithmetic.step()
        val rational = value.rational
        if (rational != null) fixed[id] = rational
        else terms.addAll(polynomialRootCondition(id, value, arithmetic))
    }
    val bools = booleans.indices.associateWith {
        arithmetic.step()
        booleans[it]
    }
    val checked = solveQfNra(
        PolynomialRealFormula.And(terms), boolCount, realCount,
        QfNraParams(cancellation = cancellation, polynomialLimits = limits),
        PolynomialCondition(bools, fixed, fixed),
    )
    arithmetic.step()
    return when (checked) {
        is QfNraResult.Unsatisfiable ->
            SourceEvidenceCheck.Rejected("polynomial source point has no feasible lowering extension")
        is QfNraResult.Unknown ->
            SourceEvidenceCheck.Unavailable("projected polynomial witness checking incomplete: ${checked.reason}")
        is QfNraResult.Satisfiable -> {
            for ((id, value) in values) {
                if (algebraic.compare(value, checked.assignment.realValue(id)) != 0) {
                    return SourceEvidenceCheck.Rejected("polynomial extension changed the original source point")
                }
            }
            arithmetic.step()
            SourceEvidenceCheck.Checked
        }
    }
}

private fun polynomialRootCondition(
    id: Int,
    value: AlgebraicReal,
    arithmetic: PolynomialArithmetic,
): List<PolynomialRealFormula> {
    val variable = PolynomialExpression.Variable(SourceRealId(id))
    val coefficients = value.definingCoefficients
    var polynomial: PolynomialExpression = polynomialConstant(coefficients.last())
    for (index in coefficients.lastIndex - 1 downTo 0) {
        arithmetic.step()
        polynomial = PolynomialExpression.Sum(listOf(
            PolynomialExpression.Product(listOf(polynomial, variable)), polynomialConstant(coefficients[index]),
        ))
    }
    return listOf(
        PolynomialRealFormula.Atom(polynomial, PolynomialRelation.EQ, polynomialConstant(BigFraction.ZERO)),
        PolynomialRealFormula.Atom(variable, PolynomialRelation.GT, polynomialConstant(value.lowerBound)),
        PolynomialRealFormula.Atom(variable, PolynomialRelation.LT, polynomialConstant(value.upperBound)),
    )
}

private fun polynomialConstant(value: BigFraction): PolynomialExpression.Constant =
    PolynomialExpression.Constant.of(value.num, value.den)
