package com.eignex.klause.lp.optimization

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
