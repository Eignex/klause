package com.eignex.klause.solver.objective

import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction

/**
 * Capture this affine objective in the exact quadratic source representation.
 * Real coefficients retain their declared binary64 values; nonfinite coefficients are rejected.
 * Boolean terms denote true source coordinates. Objective direction belongs to the invocation.
 */
fun LinearObjective.toQuadraticObjectiveSpec(): QuadraticObjectiveSpec {
    val terms = buildList {
        boolWeightValues.forEachIndexed { id, weight ->
            if (weight != 0L) add(QuadraticObjectiveTerm(
                QuadraticCoefficient.ofLong(weight), QuadraticVariable.Bool(SourceBoolId(id)),
            ))
        }
        intCoefficientValues.forEachIndexed { id, coefficient ->
            if (coefficient != 0L) add(QuadraticObjectiveTerm(
                QuadraticCoefficient.ofLong(coefficient), QuadraticVariable.Int(SourceIntId(id)),
            ))
        }
        realCoefficientValues.forEachIndexed { id, coefficient ->
            val exact = requireNotNull(BigFraction.ofDouble(coefficient)) {
                "affine objective requires finite real coefficients"
            }
            if (exact != BigFraction.ZERO) add(QuadraticObjectiveTerm(
                QuadraticCoefficient.of(exact.num, exact.den), QuadraticVariable.Real(SourceRealId(id)),
            ))
        }
    }
    return QuadraticObjectiveSpec(terms, QuadraticCoefficient.ofLong(constant))
}

/** Capture lowered affine coefficients without changing source namespaces or binary64 authority. */
fun LinearObjectiveSpec.toQuadraticObjectiveSpec(): QuadraticObjectiveSpec =
    toLinearObjective().toQuadraticObjectiveSpec()
