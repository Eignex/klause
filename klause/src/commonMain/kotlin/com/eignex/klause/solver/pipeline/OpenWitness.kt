package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.composedFixpoint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.verifyRealCoordinates
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.toLongExact

/**
 * Why [sample] is not a solution of the open source model [model], or null when it is.
 *
 * Every integer column must lie in its declared range and value set; then the model is baked with each integer column
 * pinned to its value, so every factor's propagator decides the point exactly, Booleans pinned on top. A model with
 * continuous columns needs the sample's exact real values, which a candidate completion certified against the same
 * rows by an exact LP; a floating-point real value alone decides nothing.
 */
internal fun refuteOpenWitness(model: Problem, sample: Sample): String? = when (val verdict = verifyOpenWitness(model, sample)) {
    is Verification.Accepted -> null
    is Verification.Rejected -> verdict.reason
    is Verification.Indeterminate -> verdict.reason
}

internal fun verifyOpenWitness(model: Problem, sample: Sample): Verification<Sample, Unit> {
    if (sample.exactInts?.any { !it.fitsLong() } == true) {
        return Verification.Indeterminate("wide integer coordinates require an exact theory witness check")
    }
    if (sample.bools.size != model.numBoolVars || sample.numIntVars != model.numIntVars) {
        return Verification.Rejected("assignment does not cover the model's discrete variables")
    }
    when (val verdict = verifyRealCoordinates(model, sample)) {
        is Verification.Rejected -> return verdict
        is Verification.Indeterminate -> return verdict
        is Verification.Accepted -> Unit
    }
    val bounds = model.intBounds
    for (v in 0 until model.numIntVars) {
        val x = sample.ints[v]
        if ((bounds.hasLower(v) && x < bounds.lower(v)) || (bounds.hasUpper(v) && x > bounds.upper(v))) {
            return Verification.Rejected("int $v = $x is outside its declared range")
        }
        if (model.intDomainOrNull(v)?.let { x !in it } == true) {
            return Verification.Rejected("int $v = $x is outside its declared values")
        }
    }
    val point = model.withIntDomains(Array(model.numIntVars) { IntDomain(sample.ints[it], sample.ints[it]) })
    return composedFixpoint(point.bake(), Candidate(sample, Unit), Cancellation.Never)
}

internal fun OpenTheoryAssignment.toSample(model: Problem): Sample {
    val ints = List(model.numIntVars) { parseBigInt(intValue(it)) }
    val reals = List(model.numRealVars) { TheoryCompletion.parseRational(realValue(it)) }
    return Sample(
        bools = BooleanArray(model.numBoolVars) { boolValue(it) },
        ints = if (ints.all { it.fitsLong() }) LongArray(ints.size) { ints[it].toLongExact() } else LongArray(0),
        reals = DoubleArray(reals.size) { reals[it].toDouble() },
        exactReals = reals,
        exactInts = ints,
    ).also { it.witnessCertificate = EvidenceCertificate.verified(ModelIdentity.of(model), EvidenceKind.Witness) }
}
