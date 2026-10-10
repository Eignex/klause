package com.eignex.klause.solver.result

/** Graded violations of one committed local-search assignment; this observation is not a feasible witness. */
data class LocalSearchResidual(
    /** Sum of the assignment's per-factor graded violation degrees. */
    val cost: Long,
    /** Nonzero graded violation totals, keyed by factor class name, from the same assignment as [cost]. */
    val byKind: Map<String, Long>,
)
