package com.eignex.klause.solver.result

import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo

/** Core-search observations in the invocation's original objective units; telemetry carries no proof authority. */
data class CoreGuidedStats(
    /** Completed core refutations. */
    val cores: Long = 0L,
    /** Largest refuted core, including residual threshold terms. */
    val largestCore: Int = 0,
    /** Exact accumulated objective floor, absent when no core worker ran. */
    val lowerBound: BigInt? = null,
    /** Largest finite cumulative core work allowance among reported workers. */
    val workLimit: Long? = null,
    /** Largest finite core active-time allowance, in milliseconds, among reported workers. */
    val activeMillisLimit: Long? = null,
    /** Caller-funded continuations following a completed near-attainment core. */
    val continuationGrants: Long = 0L,
) {
    /** Create core observations with resource limits and no continuations. */
    constructor(cores: Long, largestCore: Int, lowerBound: BigInt?, workLimit: Long?, activeMillisLimit: Long?) :
        this(cores, largestCore, lowerBound, workLimit, activeMillisLimit, 0L)

    /** Create core observations without resource-limit telemetry. */
    constructor(cores: Long = 0L, largestCore: Int = 0, lowerBound: BigInt? = null) :
        this(cores, largestCore, lowerBound, null, null)

    /** Copy core observations while retaining the reported resource limits. */
    fun copy(
        cores: Long = this.cores,
        largestCore: Int = this.largestCore,
        lowerBound: BigInt? = this.lowerBound,
    ): CoreGuidedStats = CoreGuidedStats(cores, largestCore, lowerBound, workLimit, activeMillisLimit, continuationGrants)

    /** Combine workers of the same objective invocation; floors take their maximum. */
    fun mergedWith(other: CoreGuidedStats): CoreGuidedStats = CoreGuidedStats(
        cores + minOf(other.cores, Long.MAX_VALUE - cores),
        maxOf(largestCore, other.largestCore),
        lowerBound?.let { left -> other.lowerBound?.let { if (left > it) left else it } ?: left } ?: other.lowerBound,
        maximumLimit(workLimit, other.workLimit),
        maximumLimit(activeMillisLimit, other.activeMillisLimit),
        continuationGrants + minOf(other.continuationGrants, Long.MAX_VALUE - continuationGrants),
    )
}

private fun maximumLimit(left: Long?, right: Long?): Long? = when {
    left == null -> right
    right == null -> left
    else -> maxOf(left, right)
}
