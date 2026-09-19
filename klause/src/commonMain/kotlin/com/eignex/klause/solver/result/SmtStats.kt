package com.eignex.klause.solver.result

import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.solver.search.SearchExplanation
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

/** Source-adapter accounting. Phase counters have distinct units/scopes and must not be summed. */
data class SourceLpWorkStats(
    /** Nonrefundable admitted source-adapter operations. */
    val operations: Long = 0L,
    /** Conservative work reservations, not measured execution. */
    val modeledWork: Long = 0L,
    /** Conservative allocation reservations, not measured bytes or RSS. */
    val modeledAllocation: Long = 0L,
    /** Active adapter time, including nested solves and owner cleanup once. */
    val activeNs: Long = 0L,
    /** Measured source-owner preparation work. */
    val preparationWork: Long = 0L,
    /** Measured source float solve work, excluding preparation. */
    val floatWork: Long = 0L,
    /** Measured continuation invocation work. */
    val continuationWork: Long = 0L,
    /** Charged refinement work, including its preceding direct-stage charges; overlaps other counters. */
    val refinementWork: Long = 0L,
) {
    /** Merge independent nonnegative deltas with saturating arithmetic. */
    fun mergedWith(other: SourceLpWorkStats): SourceLpWorkStats = SourceLpWorkStats(
        operations + minOf(other.operations, Long.MAX_VALUE - operations),
        modeledWork + minOf(other.modeledWork, Long.MAX_VALUE - modeledWork),
        modeledAllocation + minOf(other.modeledAllocation, Long.MAX_VALUE - modeledAllocation),
        activeNs + minOf(other.activeNs, Long.MAX_VALUE - activeNs),
        preparationWork + minOf(other.preparationWork, Long.MAX_VALUE - preparationWork),
        floatWork + minOf(other.floatWork, Long.MAX_VALUE - floatWork),
        continuationWork + minOf(other.continuationWork, Long.MAX_VALUE - continuationWork),
        refinementWork + minOf(other.refinementWork, Long.MAX_VALUE - refinementWork),
    )
}

/** Exact-arithmetic telemetry for complete SMT theory routes. */
data class SmtStats(
    /** Exact-theory conflicts returned to the shared search. */
    val conflicts: Long = 0,
    /** Returned conflicts with a clause-form explanation. */
    val explainedConflicts: Long = 0,
    /** Returned conflicts without a clause-form explanation. */
    val unexplainedConflicts: Long = 0,
    /** Literals across [explainedConflicts] only. */
    val conflictLiterals: Long = 0,
    /** Calls to the LIRA reduction cache, including cache hits. */
    val reductionRequests: Long = 0,
    /** Reduction-cache hits. */
    val reductionCacheHits: Long = 0,
    /** Reduction requests with a decisive bounded or infeasible result. */
    val reductionAccepted: Long = 0,
    /** Reduction requests interrupted before a decisive result. */
    val reductionDeclined: Long = 0,
    /** Reduction time including nested common-engine work. */
    val reductionNs: Long = 0,
    /** Exact source-witness candidates made available by an exact theory check. */
    val witnessCandidates: Long = 0,
    /** Existing consumer paths that accepted an exact source witness. */
    val witnessAccepted: Long = 0,
    /** Witness candidates over a source system containing a strict row. */
    val strictWitnessCandidates: Long = 0,
    /** Accepted witnesses over a source system containing a strict row. */
    val strictWitnessAccepted: Long = 0,
    /** Witness candidates with wide exact integer source data. */
    val wideWitnessCandidates: Long = 0,
    /** Accepted witnesses with wide exact integer source data. */
    val wideWitnessAccepted: Long = 0,
    /** Shared LP exact continuation invocation deltas. */
    val continuation: LpContinuationStats = LpContinuationStats(),
    /** Common-engine source adapters, separate from retired cold-simplex counters. */
    val sourceLp: SourceLpWorkStats = SourceLpWorkStats(),
) {
    /** Combine independent solve slices. */
    fun mergedWith(other: SmtStats): SmtStats = SmtStats(
        conflicts + other.conflicts,
        explainedConflicts + other.explainedConflicts,
        unexplainedConflicts + other.unexplainedConflicts,
        conflictLiterals + other.conflictLiterals,
        reductionRequests + other.reductionRequests,
        reductionCacheHits + other.reductionCacheHits,
        reductionAccepted + other.reductionAccepted,
        reductionDeclined + other.reductionDeclined,
        reductionNs + other.reductionNs,
        witnessCandidates + other.witnessCandidates,
        witnessAccepted + other.witnessAccepted,
        strictWitnessCandidates + other.strictWitnessCandidates,
        strictWitnessAccepted + other.strictWitnessAccepted,
        wideWitnessCandidates + other.wideWitnessCandidates,
        wideWitnessAccepted + other.wideWitnessAccepted,
        continuation.mergedWith(other.continuation),
        sourceLp.mergedWith(other.sourceLp),
    )
}

/** Mutable exact-SMT telemetry for one top-level open-theory request. */
internal class SmtStatsSink {
    private var sourceLp = SourceLpWorkStats()

    fun observeSourceLp(delta: SourceLpWorkStats) {
        sourceLp = sourceLp.mergedWith(delta)
    }

    private var continuation = LpContinuationStats()

    fun observeContinuation(metrics: ExactContinuationMetrics) {
        continuation = continuation.mergedWith(metrics.toStats())
    }

    private var conflicts = 0L
    private var explainedConflicts = 0L
    private var unexplainedConflicts = 0L
    private var conflictLiterals = 0L
    private var reductionRequests = 0L
    private var reductionCacheHits = 0L
    private var reductionAccepted = 0L
    private var reductionDeclined = 0L
    private var reductionNs = 0L
    private var witnessCandidates = 0L
    private var witnessAccepted = 0L
    private var strictWitnessCandidates = 0L
    private var strictWitnessAccepted = 0L
    private var wideWitnessCandidates = 0L
    private var wideWitnessAccepted = 0L

    fun observeConflict(explanation: SearchExplanation?) {
        conflicts++
        if (explanation == null || explanation.literals.isEmpty()) {
            unexplainedConflicts++
        } else {
            explainedConflicts++
            conflictLiterals += explanation.literals.size
        }
    }

    fun beginReduction(): TimeMark = Monotonic.markNow()

    fun endReduction(mark: TimeMark, cacheHit: Boolean, accepted: Boolean) {
        reductionRequests++
        if (cacheHit) reductionCacheHits++
        if (accepted) reductionAccepted++ else reductionDeclined++
        reductionNs += mark.elapsedNow().inWholeNanoseconds
    }

    fun observeWitnessCandidate(strict: Boolean, wide: Boolean) {
        witnessCandidates++
        if (strict) strictWitnessCandidates++
        if (wide) wideWitnessCandidates++
    }

    fun observeWitnessAccepted(strict: Boolean, wide: Boolean) {
        witnessAccepted++
        if (strict) strictWitnessAccepted++
        if (wide) wideWitnessAccepted++
    }

    fun snapshot(): SmtStats = SmtStats(
        conflicts, explainedConflicts, unexplainedConflicts, conflictLiterals,
        reductionRequests, reductionCacheHits, reductionAccepted, reductionDeclined, reductionNs,
        witnessCandidates, witnessAccepted, strictWitnessCandidates, strictWitnessAccepted,
        wideWitnessCandidates, wideWitnessAccepted, continuation, sourceLp,
    )
}
