package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.LinearAuthorityCapture
import com.eignex.klause.lp.optimization.LinearAuthorityCapturedQuery
import com.eignex.klause.lp.optimization.LinearAuthorityResult
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal data class SourceNumericalCutoff(val value: BigFraction, val premise: String) {
    init { require(premise.isNotBlank()) }
    fun applyTo(
        source: SourceProofContext, funding: SourceLpFunding, cancellation: Cancellation = Cancellation.Never,
    ): SourceProofContext? {
        if (cancellation() || funding.paused || source.scope.objective == null || !source.affineObjectiveAvailable) {
            return null
        }
        val coordinates = source.coordinates
        val premises = source.premises
        val objective = source.objective
        var entries = coordinates.size.toLong() + premises.size + objective.size
        funding.charge(1L + entries, 256L + entries * 48L)
        if (entries > 250_000L || cancellation() || funding.exhausted ||
            value.num.magnitudeBitLength() > 4096 || value.den.magnitudeBitLength() > 4096 ||
            source.objectiveConstant.num.magnitudeBitLength() > 4096 ||
            source.objectiveConstant.den.magnitudeBitLength() > 4096) return null
        for (row in premises.values) {
            val count = row.inequality.supportSize.toLong() + row.guards.size + row.integerPins.size
            entries += count
            funding.charge(count, count * 128L)
            if (entries > 250_000L || cancellation() || funding.paused) return null
        }
        for (coefficient in objective.values) {
            val numerator = coefficient.num.magnitudeBitLength()
            val denominator = coefficient.den.magnitudeBitLength()
            funding.charge(1L, 64L + (numerator.toLong() + denominator + 7L) / 8L)
            if (numerator > 4096 || denominator > 4096 || cancellation() || funding.paused) return null
        }
        funding.charge(1L, 256L + (value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength() +
            source.objectiveConstant.num.magnitudeBitLength() + source.objectiveConstant.den.magnitudeBitLength()) / 4L)
        if (cancellation() || funding.paused) return null
        if (premise in premises) return source.takeIf(::enforcedBy)
        val minimize = source.scope.objective.sense == ObjectiveSense.MINIMIZE
        val row = SourceInequality(objective.mapValues { (_, coefficient) ->
            if (minimize) coefficient else coefficient.negated()
        }, if (minimize) value - source.objectiveConstant else source.objectiveConstant - value, strict = true)
        return SourceProofContext(source.scope, coordinates, premises + (premise to SourcePremise(row)),
            objective, source.objectiveConstant, source.semanticsChecker, source.semantics)
    }

    fun enforcedBy(source: SourceProofContext): Boolean {
        if (source.scope.objective == null || !source.affineObjectiveAvailable) return false
        val row = source.premises[premise] ?: return false
        val minimize = source.scope.objective.sense == ObjectiveSense.MINIMIZE
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
                query.context.affineObjectiveAvailable == source.affineObjectiveAvailable &&
                query.context.objective == source.objective &&
                query.context.objectiveConstant == source.objectiveConstant &&
                query.context.semantics == source.semantics &&
                query.context.semanticsChecker === source.semanticsChecker
    }

    data class Unavailable(
        val source: SourceProofContext,
        val cutoff: SourceNumericalCutoff?,
        val reason: Reason,
    ) : SourceNumericalFeedbackResult

    enum class Reason { Cancelled, Funding, Retention, Paused, MissingQuery, Incompatible, Oversized }
}

internal data class SourceNumericalAcquisition(
    val result: LinearAuthorityResult,
    val feedback: SourceNumericalFeedbackResult,
)

// Exact-valued relaxation coordinates are guidance, including when a bound was certified.
// Latest delivery reads the journal's delivered query, so archive retention never withholds a later query.
// Prefix delivery reads only the archive and reports every query after its retention limit as unavailable.
internal class SourceNumericalFeedback(
    private val funding: SourceLpFunding,
    private val cancellation: Cancellation,
    private val latest: Boolean = true,
) {
    private var journal = LinearAuthorityCapture(64, 250_000L, 8L * 1024 * 1024, cancellation)
    private val owned = journal
    private var chargedEntries = 0L
    private var chargedBytes = 0L
    private var consumed = 0L
    var collections: Long = 0L
        private set
    var lateCollections: Long = 0L
        private set
    var unavailable: Long = 0L
        private set

    fun attach(authority: LinearAuthority) {
        require(authority.funding === funding)
        val existing = authority.capture
        if (existing == null) authority.capture = journal
        else if (existing !== journal) {
            settle()
            if (latest) journal.stopDelivery()
            journal = existing
            chargedEntries = journal.constructedEntries
            chargedBytes = journal.constructedBytes
            consumed = journal.attempted
        }
        journal.restrict(64, 250_000L, 8L * 1024 * 1024)
        if (latest) journal.deliver(250_000L, 8L * 1024 * 1024)
    }

    fun acquire(
        authority: LinearAuthority,
        source: SourceProofContext,
        cutoff: SourceNumericalCutoff? = null,
        cuts: Boolean = true,
    ): SourceNumericalAcquisition {
        attach(authority)
        val result = authority.advance(source, cancellation, cuts = cuts)
        return SourceNumericalAcquisition(result, consume(source, cutoff, result.paused))
    }

    // An external observer keeps its archive; only the delivered query this consumer requested is released.
    fun detach(authority: LinearAuthority) {
        settle()
        if (latest) journal.stopDelivery()
        if (authority.capture === owned) authority.capture = null
    }

    fun refutationSupport(record: SourceNumericalFeedbackResult.Available): SourceNumericalSupportResult =
        sourceNumericalRefutationSupport(record, funding, cancellation)

    private fun settle() {
        funding.charge(journal.constructedEntries - chargedEntries, journal.constructedBytes - chargedBytes)
        chargedEntries = journal.constructedEntries
        chargedBytes = journal.constructedBytes
    }

    fun consume(
        source: SourceProofContext,
        cutoff: SourceNumericalCutoff? = null,
        paused: Boolean = false,
    ): SourceNumericalFeedbackResult {
        settle()
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
        val query = if (latest) {
            if (journal.deliveryOverflowed) return missing(SourceNumericalFeedbackResult.Reason.Oversized)
            journal.delivered
        } else {
            val record = journal.lastRecord
            if (journal.limited) return missing(SourceNumericalFeedbackResult.Reason.Retention)
            record
        }
        if (query == null) return missing(SourceNumericalFeedbackResult.Reason.MissingQuery)
        val entries = source.coordinates.size.toLong() + source.premises.size + source.objective.size
        funding.charge(1L + entries, 64L + entries * 48L)
        val result = SourceNumericalFeedbackResult.Available(query, cutoff)
        if (!result.compatible(source, cutoff)) return missing(SourceNumericalFeedbackResult.Reason.Incompatible)
        collections++
        if (journal.lastRecord?.id != query.id) lateCollections++
        return result
    }
}
