package com.eignex.klause.solver.result

import com.eignex.klause.util.BigInt
import com.eignex.klause.util.compareTo

/** Core-search observations in the invocation's original objective units; telemetry carries no proof authority. */
data class CoreGuidedStats(
    /** Completed core transformations. */
    val cores: Long = 0L,
    /** Largest completed core, including residual threshold terms. */
    val largestCore: Int = 0,
    /** Exact accumulated objective floor, absent when no core worker ran. */
    val lowerBound: BigInt? = null,
) {
    /** Combine workers of the same objective invocation; floors take their maximum. */
    fun mergedWith(other: CoreGuidedStats): CoreGuidedStats = CoreGuidedStats(
        cores + minOf(other.cores, Long.MAX_VALUE - cores),
        maxOf(largestCore, other.largestCore),
        lowerBound?.let { left -> other.lowerBound?.let { if (left > it) left else it } ?: left } ?: other.lowerBound,
    )
}
