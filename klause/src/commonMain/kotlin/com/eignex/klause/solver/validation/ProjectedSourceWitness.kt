package com.eignex.klause.solver.validation

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation

internal fun verifyProjectedSourceWitness(
    source: Problem,
    integerChecker: BakedProblem,
    sample: Sample,
    cancellation: Cancellation,
    toleranceCheck: ((Sample) -> Boolean)?,
): Verification<Sample, Unit> {
    if (cancellation()) return Verification.Indeterminate("source verification cancelled")
    if (sample.numBoolVars != source.numBoolVars || sample.numIntVars != source.numIntVars ||
        sample.numRealVars != source.numRealVars
    ) return Verification.Rejected("assignment does not cover original source coordinates")
    for (id in 0 until source.numIntVars) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("source bounds check cancelled")
        val value = sample.intValue(id)
        if (value < source.intBounds.lower(id) || value > source.intBounds.upper(id) ||
            source.intDomainOrNull(id)?.let { value !in it } == true
        ) return Verification.Rejected("assignment violates an original integer declaration")
    }
    checkProjectedRealBounds(source, sample, cancellation)?.let { return it }
    val policy = source.semantics.effective.feasibilityTolerance
    val checked = if (policy == null || policy.isExact) verifyRealCoordinates(source, sample, cancellation)
        else verifyCapturedLinearTolerance(source, sample, policy, cancellation)
    if (cancellation()) return Verification.Indeterminate("source verification cancelled")
    when (checked) {
        is Verification.Accepted -> Unit
        is Verification.Rejected -> return checked
        is Verification.Indeterminate -> return checked
    }
    if (toleranceCheck != null) {
        val admitted = toleranceCheck(sample)
        if (cancellation()) return Verification.Indeterminate("source acceptance callback cancelled")
        if (!admitted) return Verification.Rejected("additional source acceptance filter refutes the assignment")
    }
    if (cancellation()) return Verification.Indeterminate("source verification cancelled")
    return composedFixpoint(integerChecker, Candidate(sample, Unit), cancellation,
        onWork = { cancellation.charge(it) })
}

private fun checkProjectedRealBounds(
    source: Problem,
    sample: Sample,
    cancellation: Cancellation,
): Verification<Nothing, Nothing>? {
    for (id in 0 until source.numRealVars) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("source real bounds check cancelled")
        val exact = sample.exactReals?.get(id) ?: sample.algebraicReals?.get(id)?.rational
            ?: sample.symbolicReals?.get(id)?.rational
        val policy = source.semantics.effective.feasibilityTolerance
        val lower = BigFraction.ofDouble(source.realLower[id])?.let { it - capturedSourceSlack(policy, it) }
        val upper = BigFraction.ofDouble(source.realUpper[id])?.let { it + capturedSourceSlack(policy, it) }
        if (exact != null) {
            if ((lower != null && exact < lower) || (upper != null && exact > upper)) {
                return Verification.Rejected("assignment violates an original real declaration")
            }
        } else if (sample.algebraicReals != null) {
            val value = sample.algebraicReals[id]
            if ((lower != null && value.lowerBound < lower) || (upper != null && value.upperBound > upper)) {
                return Verification.Indeterminate("source algebraic bound needs its exact comparison owner")
            }
        } else if (sample.symbolicReals != null) {
            if (lower != null || upper != null) {
                return Verification.Indeterminate("source symbolic bound needs its exact comparison owner")
            }
        } else {
            val value = sample.approximateRealValue(id)
            if (!value.isFinite() || value < source.realLower[id] || value > source.realUpper[id]) {
                return Verification.Rejected("assignment violates an original real declaration")
            }
        }
    }
    return null
}
