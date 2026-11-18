package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.narrowToNearestDoubles
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.QuadraticWitnessPublication
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.ExecutionControl

// This point with each algebraic real coordinate narrowed under [control] toward its nearest binary64 value, which
// becomes its approximate coordinate; see [narrowToNearestDoubles]. The narrowed intervals are kept, so every reader
// of the exact coordinate rounds it the same way, and a certificate naming this point names the narrowed one. A
// coordinate the narrowing leaves unsettled publishes its narrowest interval's rounded midpoint and is listed by
// [undeterminedReals]. The exact values, and so any verdict, are unchanged. Only consumers that publish a binary64
// value call this; the theories publish their exact witnesses as captured.
internal fun Sample.withNearestReals(
    control: ExecutionControl = publicationNarrowing(),
    limits: PolynomialLimits = PolynomialLimits(),
): Sample {
    val original = algebraicReals ?: return this
    val narrowed = narrowToNearestDoubles(original, control, limits)
    val exact = exactReals
    val reals = DoubleArray(numRealVars) { exact?.get(it)?.toDouble() ?: narrowed[it].approximateDouble }
    if (narrowed === original && reals.indices.all { reals[it].toBits() == approximateRealValue(it).toBits() }) {
        return this
    }
    val published = withNarrowedReals(reals, narrowed)
    val witness = witnessCertificate?.publication as? CompletedEnginePublication.Witness
    if (witness != null && witness.sample === this) {
        published.witnessCertificate = EvidenceCertificate.published(
            CompletedEnginePublication.Witness(witness.invocation, published, witness.value),
        )
    }
    return published
}

// This point with every real coordinate published as its nearest binary64 value and no rational authority, so that an
// acceptance check reads exactly the floats a frontend prints. Unlike [withNearestReals] it narrows under the
// caller's [control], since the floats decide a verdict, and it is null when a coordinate's nearest value stays
// unsettled, since no float point is then known.
internal fun Sample.publishedFloats(control: ExecutionControl, limits: PolynomialLimits): Sample? {
    val exact = exactReals
    val reals = DoubleArray(numRealVars) { id -> exact?.get(id)?.toDouble() ?: approximateRealValue(id) }
    val algebraic = algebraicReals ?: return copy(reals = reals)
    val narrowed = narrowToNearestDoubles(algebraic, control, limits)
    for (id in narrowed.indices) {
        if (exact?.get(id) == null) reals[id] = narrowed[id].nearestDouble ?: return null
    }
    return copy(reals = reals, algebraicReals = narrowed)
}

// A private bound on the arithmetic one publication spends narrowing, apart from any search or verdict allowance, so
// narrowing neither debits a search nor depends on a token that has already stopped.
internal fun publicationNarrowing(): ExecutionControl {
    var polls = 0
    return ExecutionControl { ++polls > PUBLICATION_POLLS }
}

private const val PUBLICATION_POLLS = 1 shl 16

internal fun AlgebraicReal.publishedDouble(): Double =
    narrowToNearestDoubles(listOf(this), publicationNarrowing()).single().approximateDouble

// This incumbent over its narrowed sample, whose quadratic publication names the narrowed point when it named this one.
internal fun QuadraticIncumbent.withNearestReals(
    control: ExecutionControl = publicationNarrowing(),
    limits: PolynomialLimits = PolynomialLimits(),
): QuadraticIncumbent {
    val narrowed = sample.withNearestReals(control, limits)
    if (narrowed === sample) return this
    val point = copy(sample = narrowed)
    val certificate = sample.witnessCertificate
    val publication = certificate?.quadraticPublication
    if (certificate != null && publication != null && publication.incumbent.sample === sample) {
        narrowed.witnessCertificate = EvidenceCertificate.published(
            certificate.model, QuadraticWitnessPublication(publication.invocation, point),
        )
    }
    return point
}

// This finished execution with its result incumbent and accepted publication narrowed once, after the verdict and on a
// private publication allowance. A publication naming the result's sample shares its narrowed point.
internal fun QuadraticOptimizationExecution.withNearestReals(): QuadraticOptimizationExecution {
    val incumbent = (result as? QuadraticOptimizationResult.WithIncumbent)?.incumbent
    val narrowed = incumbent?.withNearestReals()
    val accepted = acceptedIncumbent
    val publication = when {
        accepted == null -> null
        narrowed != null && accepted.incumbent === incumbent -> accepted.copy(incumbent = narrowed)
        narrowed != null && accepted.incumbent.sample === incumbent.sample ->
            accepted.copy(incumbent = accepted.incumbent.copy(sample = narrowed.sample))
        else -> accepted.copy(incumbent = accepted.incumbent.withNearestReals())
    }
    if (narrowed === incumbent && publication?.incumbent === accepted?.incumbent) return this
    val narrowedResult = when (val result = result) {
        is QuadraticOptimizationResult.Optimal -> result.copy(incumbent = checkNotNull(narrowed))
        is QuadraticOptimizationResult.UnattainedLimit -> result.copy(incumbent = checkNotNull(narrowed))
        is QuadraticOptimizationResult.Unbounded -> result.copy(incumbent = checkNotNull(narrowed))
        is QuadraticOptimizationResult.BestFound -> result.copy(incumbent = checkNotNull(narrowed))
        is QuadraticOptimizationResult.Infeasible -> result
        is QuadraticOptimizationResult.Unknown -> result
    }
    return copy(result = narrowedResult).also { it.retainPublication(publication) }
}
