package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.composedFixpoint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation

/**
 * Why [sample] is not a solution of the open source model [model], or null when it is.
 *
 * Every integer column must lie in its declared range and value set; then the model is baked with each integer column
 * pinned to its value, so every factor's propagator decides the point exactly, Booleans pinned on top. A model with
 * continuous columns needs the sample's exact real values, which a candidate completion certified against the same
 * rows by an exact LP; a floating-point real value alone decides nothing.
 */
internal fun refuteOpenWitness(model: Problem, sample: Sample): String? {
    if (sample.bools.size != model.numBoolVars || sample.ints.size != model.numIntVars) {
        return "assignment covers ${sample.bools.size}/${sample.ints.size} of the " +
            "${model.numBoolVars}/${model.numIntVars} discrete variables"
    }
    if (model.numRealVars > 0 && sample.exactReals == null) return "continuous values are not certified"
    val bounds = model.intBounds
    for (v in 0 until model.numIntVars) {
        val x = sample.ints[v]
        if ((bounds.hasLower(v) && x < bounds.lower(v)) || (bounds.hasUpper(v) && x > bounds.upper(v))) {
            return "int $v = $x is outside its declared range"
        }
        if (model.intDomainOrNull(v)?.let { x !in it } == true) return "int $v = $x is outside its declared values"
    }
    val point = model.withIntDomains(Array(model.numIntVars) { IntDomain(sample.ints[it], sample.ints[it]) })
    val verdict = composedFixpoint(point.bake(), Candidate(sample, Unit), Cancellation.Never)
    return (verdict as? Verification.Rejected)?.reason
}

/** A [Sample] of this witness for the portfolio to carry, its integers clipped where they pass the 64-bit range. */
internal fun OpenTheoryAssignment.toSampleOrPlaceholder(model: Problem): Sample = Sample(
    bools = BooleanArray(model.numBoolVars) { boolValue(it) },
    ints = LongArray(model.numIntVars) { intValue(it).toLongOrNull() ?: 0L },
)
