package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.backtrack.composedFixpoint
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong

internal fun checkQuadraticSourceWitness(
    plan: PreparedSourcePlan,
    point: Sample,
    cancellation: Cancellation,
    limits: PolynomialLimits,
): SourceEvidenceCheck = try {
    val arithmetic = PolynomialArithmetic(cancellation, limits)
    arithmetic.step()
    if (point.numBoolVars != plan.sourceBoolCount || point.numIntVars != plan.sourceIntCount ||
        point.numRealVars != plan.sourceRealCount
    ) SourceEvidenceCheck.Rejected("quadratic witness has different source coordinates")
    else when (val source = plan.originalSource) {
        is PolynomialRealProblem -> checkProjectedPolynomialWitness(source, point, cancellation, limits)
        is NonlinearIntegerProblem -> {
            val algebraic = AlgebraicArithmetic(arithmetic)
            val reals = exactQuadraticReals(point, algebraic)
            if (reals == null) SourceEvidenceCheck.Unavailable("quadratic witness has no exact real coordinates")
            else {
                val values = source.realCoordinates.withIndex().associate { it.value to reals[it.index] }
                    .toMutableMap()
                for ((id, column) in source.integerCoordinates.withIndex()) {
                    arithmetic.step()
                    values[column] = algebraic.rational(BigFraction.of(point.exactIntValue(id), BIG_ONE))
                }
                checkProjectedPolynomialFormula(source.formula, source.boolCount, source.numericCount,
                    point.bools, values, cancellation, arithmetic, limits)
            }
        }
        is Problem -> checkQuadraticIrWitness(plan, point, cancellation, arithmetic, limits)
        else -> SourceEvidenceCheck.Unavailable("quadratic source has no installed witness checker")
    }
} catch (_: PolynomialInterrupted) {
    SourceEvidenceCheck.Unavailable("quadratic source witness checking interrupted")
} catch (_: PolynomialResourceLimit) {
    SourceEvidenceCheck.Unavailable("quadratic source witness checking exceeded representation limits")
}

private fun checkQuadraticIrWitness(
    plan: PreparedSourcePlan,
    point: Sample,
    cancellation: Cancellation,
    arithmetic: PolynomialArithmetic,
    limits: PolynomialLimits,
): SourceEvidenceCheck {
    val model = plan.polynomialModel
        ?: return SourceEvidenceCheck.Unavailable("quadratic source has no retained polynomial completion")
    if (point.exactInts?.any { !it.fitsLong() } == true) {
        return SourceEvidenceCheck.Rejected("quadratic finite source integer is outside its declared range")
    }
    val discrete = Sample(point.bools, point.ints)
    val finite = plan.finiteFactorProjections?.let { model.finite.bake(it, cancellation) }
        ?: model.finite.bake(cancellation)
    when (val checked = composedFixpoint(finite, Candidate(discrete, Unit), cancellation,
        enforcingPeers = model.finiteEnforcingFactors)) {
        is Verification.Rejected -> return SourceEvidenceCheck.Rejected(checked.reason)
        is Verification.Indeterminate -> return SourceEvidenceCheck.Unavailable(checked.reason)
        is Verification.Accepted -> Unit
    }
    val algebraic = AlgebraicArithmetic(arithmetic)
    val reals = exactQuadraticReals(point, algebraic)
        ?: return SourceEvidenceCheck.Unavailable("quadratic witness has no exact real coordinates")
    val values = reals.withIndex().associate { it.index to it.value }.toMutableMap()
    for ((id, column) in model.integerColumns) {
        arithmetic.step()
        values[column] = algebraic.rational(BigFraction.of(point.exactIntValue(id), BIG_ONE))
    }
    return checkProjectedPolynomialFormula(model.service.formula, model.service.boolCount, model.service.realCount,
        point.bools, values, cancellation, arithmetic, limits)
}

private fun exactQuadraticReals(point: Sample, arithmetic: AlgebraicArithmetic): List<AlgebraicReal>? =
    point.algebraicReals ?: point.exactReals?.map(arithmetic::rational)
        ?: if (point.numRealVars == 0) emptyList() else null
