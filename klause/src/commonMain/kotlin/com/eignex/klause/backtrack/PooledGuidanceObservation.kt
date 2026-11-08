package com.eignex.klause.backtrack

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.util.numbers.magnitudeBitLength

internal enum class PooledGuidanceKind { IMPORT, LOCAL, ATTEMPT, COMMIT }

internal data class PooledGuidanceObservation(
    val kind: PooledGuidanceKind,
    val preferred: Boolean,
    val site: String? = null,
    val version: Long? = null,
    val objective: Double? = null,
    val localObjective: Double? = null,
    val localExactObjective: String? = null,
    val lastCutoff: Double? = null,
    val rootPending: Boolean? = null,
    val decision: String? = null,
    val level: Int? = null,
)

internal interface PooledGuidanceObserver : SearchBranchAttemptObserver {
    fun accepts(kind: PooledGuidanceKind): Boolean
    fun onGuidance(observation: PooledGuidanceObservation)
}

internal inline fun SearchBranchAttemptObserver?.observeGuidance(
    kind: PooledGuidanceKind,
    observation: () -> PooledGuidanceObservation,
) {
    val observer = this as? PooledGuidanceObserver ?: return
    if (observer.accepts(kind)) observer.onGuidance(observation())
}

internal fun BigFraction.guidanceText(): String? =
    takeIf { num.magnitudeBitLength() <= 2048 && den.magnitudeBitLength() <= 2048 }?.toString()

internal fun SearchDecision.guidanceText(): String = if (this is SearchDecision.Theory) "Theory" else toString()
