package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearActiveRefutation
import com.eignex.klause.lp.optimization.LinearPinnedRefutation
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.util.Cancellation

internal sealed interface SourceNumericalSupportResult {
    data class Available(
        val feedback: SourceNumericalFeedbackResult.Available,
        val coordinates: Set<SourceCoordinate>,
        val premises: Set<String>,
        val assumptions: Set<SourceDerivation.Assumption>,
    ) : SourceNumericalSupportResult

    data class Unavailable(
        val feedback: SourceNumericalFeedbackResult.Available,
        val reason: Reason,
    ) : SourceNumericalSupportResult

    enum class Reason { Unrequested, NoRefutation, Unsupported, Limit, Cancelled, Funding }
}

// This visits captured proof references for ranking; it neither reduces nor verifies a proof scope.
internal fun sourceNumericalRefutationSupport(
    feedback: SourceNumericalFeedbackResult.Available,
    funding: SourceLpFunding,
    cancellation: Cancellation,
): SourceNumericalSupportResult {
    val query = feedback.query
    funding.charge(1L, 64L)
    if (cancellation()) return SourceNumericalSupportResult.Unavailable(feedback,
        SourceNumericalSupportResult.Reason.Cancelled)
    if (funding.paused) return SourceNumericalSupportResult.Unavailable(feedback,
        SourceNumericalSupportResult.Reason.Funding)
    if (!query.projection.refutation) return SourceNumericalSupportResult.Unavailable(feedback,
        SourceNumericalSupportResult.Reason.Unrequested)
    val evidence = query.refutation ?: return SourceNumericalSupportResult.Unavailable(feedback,
        SourceNumericalSupportResult.Reason.NoRefutation)
    val coordinates = linkedSetOf<SourceCoordinate>()
    val premises = linkedSetOf<String>()
    val assumptions = linkedSetOf<SourceDerivation.Assumption>()
    var remaining = 4096
    var failure = SourceNumericalSupportResult.Reason.Unsupported
    fun spend(entries: Int): Boolean {
        funding.charge(entries.toLong(), entries * 64L)
        remaining -= entries
        failure = when {
            cancellation() -> SourceNumericalSupportResult.Reason.Cancelled
            funding.paused -> SourceNumericalSupportResult.Reason.Funding
            remaining < 0 -> SourceNumericalSupportResult.Reason.Limit
            else -> return true
        }
        return false
    }
    val original = query.context.premises
    val numeric = query.numeric.premises
    val lower = feedback.scope.condition.lower
    val upper = feedback.scope.condition.upper
    if (!spend(original.size + numeric.size + lower.size + upper.size)) {
        return SourceNumericalSupportResult.Unavailable(feedback, failure)
    }
    fun add(values: Collection<SourceCoordinate>): Boolean {
        if (!spend(values.size)) return false
        coordinates.addAll(values)
        return true
    }
    fun visit(proof: SourceDerivation, depth: Int): Boolean {
        if (!spend(1)) return false
        if (depth >= 64) {
            failure = SourceNumericalSupportResult.Reason.Limit
            return false
        }
        return when (proof) {
            is LinearPinnedRefutation -> visit(proof.conflict, depth + 1)
            is LinearActiveRefutation -> visit(proof.conflict, depth + 1)
            is SourceDerivation.Premise -> {
                val row = original[proof.id] ?: numeric[proof.id] ?: return false
                if (!add(row.inequality.coefficients.keys) || !add(row.integerPins.keys) ||
                    !add(row.guards.keys.map { SourceCoordinate(SourceCoordinate.Kind.Boolean, it) })) return false
                premises += proof.id
                true
            }
            is SourceDerivation.Assumption -> {
                val bounds = if (proof.upper) upper else lower
                if (proof.coordinate !in bounds || !add(listOf(proof.coordinate))) return false
                assumptions += proof
                true
            }
            is SourceDerivation.Combination -> proof.terms.let { terms -> spend(terms.size) && terms.all {
                it.weight.isZero || visit(it.derivation, depth + 1)
            } }
            is SourceDerivation.IntegerRounding -> visit(proof.inequality, depth + 1)
            is SourceDerivation.MixedIntegerRounding -> visit(proof.inequality, depth + 1) &&
                proof.bounds.let { bounds -> spend(bounds.size) && bounds.all { visit(it, depth + 1) } }
            else -> false
        }
    }
    if (evidence.claim != SourceClaim.Refutation || evidence.scope != feedback.scope || !spend(1) ||
        !add(feedback.scope.condition.bools.keys.map { SourceCoordinate(SourceCoordinate.Kind.Boolean, it) }) ||
        !visit(evidence.derivation, 0) || !spend(coordinates.size + premises.size + assumptions.size)) {
        return SourceNumericalSupportResult.Unavailable(feedback, failure)
    }
    return SourceNumericalSupportResult.Available(feedback, coordinates.toSet(), premises.toSet(), assumptions.toSet())
}
