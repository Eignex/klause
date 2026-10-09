package com.eignex.klause.solver.result

/** Inclusive elapsed time and engine work for an LP phase on one consumer route. Nested phase times overlap. */
data class LpPhaseStats(
    /** Completed invocations, including declines. */
    val calls: Long = 0L,
    /** Inclusive elapsed nanoseconds; nested phases overlap. */
    val nanos: Long = 0L,
    /** Cleanup simplex work; other phases expose their cost through elapsed time. */
    val work: Long = 0L,
    /** Cleanup simplex pivots. */
    val pivots: Long = 0L,
    /** Exact-dual refinement steps, including abandoned attempts. */
    val steps: Long = 0L,
    /** Completed invocations by termination, verdict or decline reason. */
    val outcomes: Map<String, Long> = emptyMap(),
) {
    /** Combine independent solve observations. */
    fun mergedWith(other: LpPhaseStats) = LpPhaseStats(
        calls + other.calls,
        nanos + other.nanos,
        work + other.work,
        pivots + other.pivots,
        steps + other.steps,
        (outcomes.keys + other.outcomes.keys).associateWith { (outcomes[it] ?: 0L) + (other.outcomes[it] ?: 0L) },
    )
}

internal fun mergePhaseStats(first: Map<String, LpPhaseStats>, second: Map<String, LpPhaseStats>) =
    (first.keys + second.keys).associateWith {
        (first[it] ?: LpPhaseStats()).mergedWith(second[it] ?: LpPhaseStats())
    }
