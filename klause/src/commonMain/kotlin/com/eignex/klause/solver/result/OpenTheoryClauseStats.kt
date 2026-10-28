package com.eignex.klause.solver.result

/** Shared learned-clause telemetry collected during one complete open-theory solve. */
data class OpenTheoryClauseStats(
    /** Distinct clauses accepted by the shared learned store. */
    val learned: Long = 0,
    /** Equivalent learned clauses rejected because the store already retained them. */
    val relearned: Long = 0,
    /** Restart boundaries completed by the shared search session. */
    val restarts: Long = 0,
    /** Database reductions that removed at least one learned clause. */
    val reductions: Long = 0,
    /** Learned clauses dropped by reductions. */
    val dropped: Long = 0,
    /** Clauses retained when the solve terminated. */
    val retained: Long = 0,
    /** Largest number of learned clauses retained at one time. */
    val peakRetained: Long = 0,
    /** Learned-clause watch entries inspected by propagation. */
    val watchVisits: Long = 0,
    /** Shared conflicts resolved to an asserting first-UIP clause. */
    val assertingConflicts: Long = 0,
    /** Shared conflicts retained with chronological fallback because resolution could not make them asserting. */
    val nonAssertingConflicts: Long = 0,
    /** Wall time spent selecting retained clauses and rebuilding their watch lists, in nanoseconds. */
    val reductionNanos: Long = 0,
    /** Shared learned-object lifetime counters, grouped by kind and stage. */
    val learning: Map<String, Long> = emptyMap(),
) {
    /** Construct observations without learned-object lifetime telemetry. */
    constructor(
        learned: Long = 0,
        relearned: Long = 0,
        restarts: Long = 0,
        reductions: Long = 0,
        dropped: Long = 0,
        retained: Long = 0,
        peakRetained: Long = 0,
        watchVisits: Long = 0,
        assertingConflicts: Long = 0,
        nonAssertingConflicts: Long = 0,
        reductionNanos: Long = 0,
    ) : this(
        learned,
        relearned,
        restarts,
        reductions,
        dropped,
        retained,
        peakRetained,
        watchVisits,
        assertingConflicts,
        nonAssertingConflicts,
        reductionNanos,
        emptyMap(),
    )

    /** Copy observations while retaining learned-object lifetime telemetry. */
    fun copy(
        learned: Long = this.learned,
        relearned: Long = this.relearned,
        restarts: Long = this.restarts,
        reductions: Long = this.reductions,
        dropped: Long = this.dropped,
        retained: Long = this.retained,
        peakRetained: Long = this.peakRetained,
        watchVisits: Long = this.watchVisits,
        assertingConflicts: Long = this.assertingConflicts,
        nonAssertingConflicts: Long = this.nonAssertingConflicts,
        reductionNanos: Long = this.reductionNanos,
    ): OpenTheoryClauseStats = OpenTheoryClauseStats(
        learned,
        relearned,
        restarts,
        reductions,
        dropped,
        retained,
        peakRetained,
        watchVisits,
        assertingConflicts,
        nonAssertingConflicts,
        reductionNanos,
        learning,
    )

    /** Combine counters from independent solve slices. */
    fun mergedWith(other: OpenTheoryClauseStats): OpenTheoryClauseStats = OpenTheoryClauseStats(
        learned + other.learned,
        relearned + other.relearned,
        restarts + other.restarts,
        reductions + other.reductions,
        dropped + other.dropped,
        retained + other.retained,
        maxOf(peakRetained, other.peakRetained),
        watchVisits + other.watchVisits,
        assertingConflicts + other.assertingConflicts,
        nonAssertingConflicts + other.nonAssertingConflicts,
        reductionNanos + other.reductionNanos,
        mergeLearningCounts(learning, other.learning),
    )
}
