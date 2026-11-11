package com.eignex.klause.lp.optimization

import com.eignex.klause.solver.evidence.SourceScope

internal data class LinearAuthorityFractionalAttempt(
    val query: LinearAuthorityCapturedQuery?,
    val before: Map<String, Long?>,
    val after: Map<String, Long?>,
    val outcome: String,
    val rejections: List<LinearAuthorityAggregateRejection>,
    val events: Map<String, Long>,
    val rejectedAttempts: Long,
    val rejectionsLimited: Boolean,
    val observationWork: Long,
    val observationAllocation: Long,
)

internal class LinearAuthorityFractionalObservation(
    val query: LinearAuthorityCapturedQuery?,
    val before: Map<String, Long?>,
    val rejected: LinearAuthorityAggregateRejections,
    val work: Long,
    val allocation: Long,
)

internal data class LinearAuthorityFractionalQueryCost(
    val scope: SourceScope,
    val installed: Set<Int>,
    val before: Map<String, Long?>,
    val after: Map<String, Long?>?,
    val outcome: String,
    val calls: Long,
    val pauses: Long,
    val observationWork: Long,
    val observationAllocation: Long,
)

internal class LinearAuthorityFractionalQueryObservation(
    val scope: SourceScope,
    val installed: Set<Int>,
    val before: Map<String, Long?>,
    var work: Long,
    val allocation: Long,
) {
    var calls: Long = 0L
    var pauses: Long = 0L
}
