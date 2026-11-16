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
    /** Charged source-owner preparation work, including sparse construction reservations. */
    val preparationWork: Long = 0L,
    /** Measured source float solve work, excluding preparation. */
    val floatWork: Long = 0L,
    /** Measured continuation invocation work. */
    val continuationWork: Long = 0L,
    /** Charged refinement work, including its preceding direct-stage charges; overlaps other counters. */
    val refinementWork: Long = 0L,
    /** Metered bound-only certificate work, excluding floating solving and source preparation. */
    val boundCertificationWork: Long = 0L,
    /** Metered Farkas certificate work, excluding floating solving and source lifting. */
    val conflictCertificationWork: Long = 0L,
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
        boundCertificationWork + minOf(other.boundCertificationWork, Long.MAX_VALUE - boundCertificationWork),
        conflictCertificationWork + minOf(other.conflictCertificationWork, Long.MAX_VALUE - conflictCertificationWork),
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
    /** Common-engine source adapter counters. */
    val sourceLp: SourceLpWorkStats = SourceLpWorkStats(),
    /** Openings admitted to the complete pure-real difference graph. */
    val realGraphOpenings: Long = 0L,
    /** Completed primitive preference tables captured from an existing graph point. */
    val graphPointHintCaptures: Long = 0L,
    /** Completed primitive preference tables captured from an existing LP point. */
    val lpPointHintCaptures: Long = 0L,
    /** Live default consultations that released a graph-first opening's optional hints. */
    val defaultGraphHintReleases: Long = 0L,
    /** Compiled Boolean count of the complete traversal that observed [completeSearchMinUnassignedBooleans]. */
    val completeSearchBooleanVariables: Int? = null,
    /** Smallest unresolved compiled Boolean count observed by a complete traversal, excluding private queries. */
    val completeSearchMinUnassignedBooleans: Int? = null,
    /** LP attempts with every compiled Boolean assigned; null until a complete traversal is observed. */
    val completeSearchLinearChecks: Long? = null,
    /** Returned continuous points before source acceptance; null until a complete traversal is observed. */
    val completeSearchLinearPoints: Long? = null,
    /** Returned LP verdict and stage observations before the caller stop check; excludes private queries. */
    val completeSearchLinearOutcomes: Map<String, Long>? = null,
    /** Complete-traversal branch and mixed recovery stages; null until observed. */
    val completeSearchStages: Map<String, Long>? = null,
) {
    /** Combine independent solve slices. */
    fun mergedWith(other: SmtStats): SmtStats {
        val minimum = when {
            completeSearchMinUnassignedBooleans == null -> other
            other.completeSearchMinUnassignedBooleans == null -> this
            other.completeSearchMinUnassignedBooleans < completeSearchMinUnassignedBooleans -> other
            else -> this
        }
        return SmtStats(
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
            realGraphOpenings + other.realGraphOpenings,
            graphPointHintCaptures + other.graphPointHintCaptures,
            lpPointHintCaptures + other.lpPointHintCaptures,
            defaultGraphHintReleases + other.defaultGraphHintReleases,
            minimum.completeSearchBooleanVariables, minimum.completeSearchMinUnassignedBooleans,
            completeSearchLinearChecks?.let { it + (other.completeSearchLinearChecks ?: 0L) }
                ?: other.completeSearchLinearChecks,
            completeSearchLinearPoints?.let { it + (other.completeSearchLinearPoints ?: 0L) }
                ?: other.completeSearchLinearPoints,
            mergeCompleteSearchCounts(completeSearchLinearOutcomes, other.completeSearchLinearOutcomes),
            mergeCompleteSearchCounts(completeSearchStages, other.completeSearchStages),
        )
    }
}

private fun mergeCompleteSearchCounts(
    first: Map<String, Long>?, second: Map<String, Long>?,
): Map<String, Long>? = when {
    first == null -> second
    second == null -> first
    else -> (first.keys + second.keys).associateWith { (first[it] ?: 0L) + (second[it] ?: 0L) }
}

/** Mutable exact-SMT telemetry for one top-level open-theory request. */
internal class SmtStatsSink {
    val hintUsage = OpenHintUsageSink()

    private var sourceLp = SourceLpWorkStats()

    fun observeSourceLp(delta: SourceLpWorkStats) {
        sourceLp = sourceLp.mergedWith(delta)
    }

    private var continuation = LpContinuationStats()

    fun observeContinuation(metrics: ExactContinuationMetrics) {
        continuation = continuation.mergedWith(metrics.toStats())
    }

    fun observeContinuationRejection(key: String, count: Long) {
        require(count > 0L)
        val before = continuation.declines[key] ?: 0L
        continuation = continuation.copy(declines = continuation.declines +
            (key to (before + minOf(count, Long.MAX_VALUE - before))))
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
    private var realGraphOpenings = 0L
    private var graphPointHintCaptures = 0L
    private var lpPointHintCaptures = 0L
    private var defaultGraphHintReleases = 0L
    private var completeSearchBooleanVariables: Int? = null
    private var completeSearchMinUnassignedBooleans: Int? = null
    private var completeSearchLinearChecks: Long? = null
    private var completeSearchLinearPoints: Long? = null
    private var completeSearchLinearOutcomes: MutableMap<String, Long>? = null
    private var completeSearchStages: MutableMap<String, Long>? = null

    fun observeCompleteSearchBooleans(variables: Int, unassigned: Int) {
        if (completeSearchLinearChecks == null) completeSearchLinearChecks = 0L
        if (completeSearchLinearPoints == null) completeSearchLinearPoints = 0L
        if (completeSearchLinearOutcomes == null) completeSearchLinearOutcomes = linkedMapOf()
        if (completeSearchStages == null) completeSearchStages = linkedMapOf()
        val previous = completeSearchMinUnassignedBooleans
        if (previous == null || unassigned < previous) {
            completeSearchBooleanVariables = variables
            completeSearchMinUnassignedBooleans = unassigned
        }
    }

    fun observeCompleteSearchLinearCheck() {
        completeSearchLinearChecks = (completeSearchLinearChecks ?: 0L) + 1L
    }

    fun observeCompleteSearchLinearOutcome(key: String) {
        val outcomes = checkNotNull(completeSearchLinearOutcomes)
        outcomes[key] = (outcomes[key] ?: 0L) + 1L
    }

    fun observeCompleteSearchStage(key: String, count: Long = 1L) {
        val stages = checkNotNull(completeSearchStages)
        stages[key] = (stages[key] ?: 0L) + count
    }

    fun observeCompleteSearchLinearPoint() {
        completeSearchLinearPoints = (completeSearchLinearPoints ?: 0L) + 1L
    }

    fun observeRealGraphOpening() {
        realGraphOpenings++
    }

    fun observePointHintCapture(graph: Boolean) {
        if (graph) graphPointHintCaptures++ else lpPointHintCaptures++
    }

    fun observeDefaultGraphHintRelease() {
        defaultGraphHintReleases++
    }

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
        realGraphOpenings, graphPointHintCaptures, lpPointHintCaptures, defaultGraphHintReleases,
        completeSearchBooleanVariables, completeSearchMinUnassignedBooleans,
        completeSearchLinearChecks, completeSearchLinearPoints, completeSearchLinearOutcomes?.toMap(),
        completeSearchStages?.toMap(),
    )
}
