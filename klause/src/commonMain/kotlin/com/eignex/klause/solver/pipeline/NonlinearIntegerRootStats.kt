package com.eignex.klause.solver.pipeline

/** Invocation-wide observations of integer root admission, summed across private cursors. */
data class NonlinearIntegerRootStats(
    /** Mandatory unary equalities whose complete integer root sets were admitted. */
    val admittedEquations: Long,
    /** Unary equalities declined as constant differences or by representation or work limits. */
    val declinedEquations: Long,
    /** Cursor entries reusing a completed admission instead of isolating roots again. */
    val reusedAdmissions: Long,
    /** Cursor scopes refuted because a coordinate's admitted roots miss its integer window. */
    val refutedScopes: Long,
    /** Candidate tuples skipped before completion because a coordinate is not an admitted root. */
    val excludedTuples: Long,
    /** Polynomial checks requested, including relaxation and real repair queries. */
    val completionChecks: Long,
)

internal fun integerRootStats(
    params: NonlinearIntegerTheoryParams,
    workers: List<NonlinearIntegerInvocation>,
): NonlinearIntegerRootStats? {
    if (!params.admitIntegerRoots) return null
    return NonlinearIntegerRootStats(
        workers.sumOf { it.rootCounts.admitted }, workers.sumOf { it.rootCounts.declined },
        workers.sumOf { it.rootCounts.reused }, workers.sumOf { it.rootCounts.refuted },
        workers.sumOf { it.rootCounts.excluded }, workers.sumOf { it.completionChecks },
    )
}
