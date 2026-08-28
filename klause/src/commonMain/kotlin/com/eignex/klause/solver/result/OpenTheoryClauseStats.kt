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
) {
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
    )
}
