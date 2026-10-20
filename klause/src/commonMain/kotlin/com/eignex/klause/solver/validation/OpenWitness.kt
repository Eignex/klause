package com.eignex.klause.solver.validation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import kotlin.coroutines.cancellation.CancellationException

/*
 * Why [sample] is not a solution of the open source model [model], or null when it is.
 *
 * Every integer column must lie in its declared range and value set; then the model is baked with each integer column
 * pinned to its value and Booleans pinned on top. Exact real checking covers whole relations that touch real
 * coordinates before the remaining discrete relations enter propagation. A floating-point real value alone
 * decides nothing.
 */
internal fun refuteOpenWitness(model: Problem, sample: Sample): String? =
    when (val verdict = verifyOpenWitness(model, sample)) {
        is Verification.Accepted -> null
        is Verification.Rejected -> verdict.reason
        is Verification.Indeterminate -> verdict.reason
    }

internal fun verifyOpenWitness(
    model: Problem, sample: Sample, cancellation: Cancellation = Cancellation.Never,
): Verification<Sample, Unit> = if (cancellation()) {
    Verification.Indeterminate("open witness check cancelled")
} else verifyOpenWitness(model, sample, cancellation, PropagationProblem(model))

internal fun verifyOpenWitness(
    model: Problem, sample: Sample, cancellation: Cancellation, projection: PropagationProblem,
    onWork: (Long) -> Unit = {},
): Verification<Sample, Unit> {
    require(projection.problem === model) { "witness projection belongs to a different source" }
    if (cancellation()) return Verification.Indeterminate("open witness check cancelled")
    if (sample.exactInts?.any { !it.fitsLong() } == true) {
        return Verification.Indeterminate("wide integer coordinates require an exact theory witness check")
    }
    if (sample.numBoolVars != model.numBoolVars || sample.numIntVars != model.numIntVars) {
        return Verification.Rejected("assignment does not cover the model's discrete variables")
    }
    when (val verdict = verifyRealCoordinates(model, sample, cancellation)) {
        is Verification.Rejected -> return verdict
        is Verification.Indeterminate -> return verdict
        is Verification.Accepted -> Unit
    }
    val bounds = model.intBounds
    for (v in 0 until model.numIntVars) {
        cancellation.charge(1L)
        if (cancellation()) return Verification.Indeterminate("open witness check cancelled")
        val x = sample.intValue(v)
        if ((bounds.hasLower(v) && x < bounds.lower(v)) || (bounds.hasUpper(v) && x > bounds.upper(v))) {
            return Verification.Rejected("int $v = $x is outside its declared range")
        }
        if (model.intDomainOrNull(v)?.let { x !in it } == true) {
            return Verification.Rejected("int $v = $x is outside its declared values")
        }
    }
    val retained = ArrayList<Int>()
    for (id in model.factors.indices) {
        cancellation.charge(1L)
        if (cancellation()) return Verification.Indeterminate("open witness check cancelled")
        if (model.factors[id].variables.reals.isEmpty()) retained.add(id)
    }
    val discrete = model.withFactors(Array(retained.size) { model.factors[retained[it]] })
    cancellation.charge(model.numIntVars.toLong())
    if (cancellation()) return Verification.Indeterminate("open witness check cancelled")
    val point = discrete.withIntDomains(Array(model.numIntVars) { IntDomain(sample.intValue(it), sample.intValue(it)) })
    return try {
        composedFixpoint(projection.bakeRetainedView(point, retained.toIntArray(), cancellation),
            Candidate(sample, Unit), cancellation, onWork = onWork)
    } catch (_: CancellationException) {
        Verification.Indeterminate("open witness check cancelled")
    }
}
