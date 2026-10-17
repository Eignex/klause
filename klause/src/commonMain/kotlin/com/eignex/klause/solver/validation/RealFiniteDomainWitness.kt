package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation

internal fun checkRealFiniteDomains(
    source: Problem,
    sample: Sample,
    cancellation: Cancellation,
): Verification<List<BigFraction>, Unit> {
    return if (sample.numRealVars != source.numRealVars) {
        Verification.Rejected("assignment does not cover the real variables")
    } else {
        finiteDomainCoordinates(source, sample, cancellation)
    }
}

private fun finiteDomainCoordinates(
    source: Problem,
    sample: Sample,
    cancellation: Cancellation,
): Verification<List<BigFraction>, Unit> {
    cancellation.charge(source.numRealVars.toLong())
    if (cancellation()) return Verification.Indeterminate("finite real membership check cancelled")
    val reals = Array<BigFraction?>(source.numRealVars) { id ->
        sample.exactReals?.get(id) ?: sample.algebraicReals?.get(id)?.rational
            ?: sample.symbolicReals?.get(id)?.rational
    }
    for (factor in source.factors) {
        cancellation.charge(1)
        if (cancellation()) return Verification.Indeterminate("finite real membership check cancelled")
        if (factor !is RealFiniteDomain) continue
        if (factor.size == 0) return Verification.Rejected("real ${factor.variable} has an empty declared value set")
        val id = factor.variable
        val exact = sample.exactReals?.get(id)
        val algebraic = sample.algebraicReals?.get(id) ?: sample.symbolicReals?.get(id)?.algebraic
        if (exact == null && algebraic == null) {
            return Verification.Indeterminate("finite real membership has no comparable exact coordinate")
        }
        var member: BigFraction? = null
        for (index in 0 until factor.size) {
            cancellation.charge(1)
            if (cancellation()) return Verification.Indeterminate("finite real membership check cancelled")
            val value = requireNotNull(BigFraction.ofDouble(factor.valueAt(index)))
            val matches = if (exact != null) exact == value else {
                val root = requireNotNull(algebraic)
                root.rational?.let { it == value } ?: isolatedRationalRoot(root.definingCoefficients,
                    root.lowerBound, root.upperBound, value, cancellation)
                    ?: return Verification.Indeterminate("finite real membership check cancelled")
            }
            if (matches) {
                member = value
                break
            }
        }
        if (cancellation()) return Verification.Indeterminate("finite real membership check cancelled")
        if (member == null) return Verification.Rejected("real ${factor.variable} is outside its declared values")
        reals[id] = member
    }
    if (cancellation()) return Verification.Indeterminate("finite real membership check cancelled")
    if (reals.any { it == null }) return Verification.Indeterminate("continuous values are not rationally certified")
    return Verification.Accepted(Candidate(reals.map { requireNotNull(it) }, Unit))
}

private fun isolatedRationalRoot(
    coefficients: List<BigFraction>,
    lower: BigFraction,
    upper: BigFraction,
    value: BigFraction,
    cancellation: Cancellation,
): Boolean? {
    if (cancellation()) return null
    if (value <= lower || value >= upper) return false
    var residual = BigFraction.ZERO
    for (index in coefficients.indices.reversed()) {
        cancellation.charge(1)
        if (cancellation()) return null
        residual = residual * value + coefficients[index]
    }
    return if (cancellation()) null else residual.isZero
}
