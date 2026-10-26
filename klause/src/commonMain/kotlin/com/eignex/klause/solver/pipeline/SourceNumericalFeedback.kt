package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.LinearAuthorityCapture
import com.eignex.klause.lp.optimization.LinearAuthorityCapturedQuery
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation

internal data class SourceNumericalCutoff(val value: BigFraction, val premise: String) {
    fun enforcedBy(source: SourceProofContext): Boolean {
        if (source.scope.objective == null || !source.affineObjectiveAvailable) return false
        val row = source.premises[premise] ?: return false
        val minimize = source.scope.objective?.sense == ObjectiveSense.MINIMIZE
        val coefficients = source.objective.mapValues { (_, value) -> if (minimize) value else value.negated() }
        val rhs = if (minimize) value - source.objectiveConstant else source.objectiveConstant - value
        return row.guards.isEmpty() && row.integerPins.isEmpty() && row.inequality.strict &&
            row.inequality.coefficients == coefficients && row.inequality.rhs == rhs
    }
}

internal sealed interface SourceNumericalFeedbackResult {
    data class Available(
        val query: LinearAuthorityCapturedQuery,
        val cutoff: SourceNumericalCutoff?,
    ) : SourceNumericalFeedbackResult {
        val scope: SourceScope get() = query.context.scope

        fun compatible(source: SourceProofContext, cutoff: SourceNumericalCutoff?): Boolean =
            this.cutoff == cutoff && scope == source.scope &&
                (cutoff == null || cutoff.enforcedBy(source)) &&
                query.context.coordinates == source.coordinates && query.context.premises == source.premises &&
                query.context.objective == source.objective &&
                query.context.objectiveConstant == source.objectiveConstant &&
                query.context.semantics == source.semantics
    }

    data class Unavailable(
        val source: SourceProofContext,
        val cutoff: SourceNumericalCutoff?,
        val reason: Reason,
    ) : SourceNumericalFeedbackResult

    enum class Reason { Cancelled, Funding, Retention, Paused, MissingQuery, Incompatible }
}

// Exact-valued relaxation coordinates are guidance, including when a bound was certified.
internal class SourceNumericalFeedback(
    private val funding: SourceLpFunding,
    private val cancellation: Cancellation,
) {
    private var journal = LinearAuthorityCapture(64, 250_000L, 8L * 1024 * 1024, cancellation)
    private val owned = journal
    private var chargedEntries = 0L
    private var chargedBytes = 0L
    private var consumed = 0L
    var collections: Long = 0L
        private set
    var unavailable: Long = 0L
        private set

    fun attach(authority: LinearAuthority) {
        require(authority.funding === funding)
        val existing = authority.capture
        if (existing == null) authority.capture = journal
        else if (existing !== journal) {
            journal = existing
            chargedEntries = journal.entries
            chargedBytes = journal.bytes
            consumed = journal.attempted
        }
    }

    fun acquire(
        authority: LinearAuthority,
        source: SourceProofContext,
        cutoff: SourceNumericalCutoff? = null,
        cuts: Boolean = true,
    ): SourceNumericalFeedbackResult {
        attach(authority)
        val result = authority.advance(source, cancellation, cuts = cuts)
        return consume(source, cutoff, result.paused)
    }

    fun detach(authority: LinearAuthority) {
        if (authority.capture === owned) authority.capture = null
    }

    fun consume(
        source: SourceProofContext,
        cutoff: SourceNumericalCutoff? = null,
        paused: Boolean = false,
    ): SourceNumericalFeedbackResult {
        funding.charge(journal.entries - chargedEntries, journal.bytes - chargedBytes)
        chargedEntries = journal.entries
        chargedBytes = journal.bytes
        val previous = consumed
        consumed = journal.attempted
        fun missing(reason: SourceNumericalFeedbackResult.Reason): SourceNumericalFeedbackResult {
            unavailable++
            funding.charge(1L, 64L)
            return SourceNumericalFeedbackResult.Unavailable(source, cutoff, reason)
        }
        if (cancellation()) return missing(SourceNumericalFeedbackResult.Reason.Cancelled)
        if (funding.exhausted) return missing(SourceNumericalFeedbackResult.Reason.Funding)
        if (paused) return missing(SourceNumericalFeedbackResult.Reason.Paused)
        if (consumed == previous) return missing(SourceNumericalFeedbackResult.Reason.MissingQuery)
        val query = journal.lastRecord
        if (journal.limited) return missing(SourceNumericalFeedbackResult.Reason.Retention)
        if (query == null) return missing(SourceNumericalFeedbackResult.Reason.MissingQuery)
        val entries = source.coordinates.size.toLong() + source.premises.size + source.objective.size
        funding.charge(1L + entries, 64L + entries * 48L)
        val result = SourceNumericalFeedbackResult.Available(query, cutoff)
        if (!result.compatible(source, cutoff)) return missing(SourceNumericalFeedbackResult.Reason.Incompatible)
        collections++
        return result
    }
}
