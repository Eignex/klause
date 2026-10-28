package com.eignex.klause.solver.pipeline

/** Invocation-wide observations of bounded integer residue evaluation. */
data class NonlinearIntegerResidueStats(
    /** Rings with a found support or a completely exhausted support space. */
    val completedRings: Long,
    /** Shared-coordinate residue tuples evaluated, including unfinished rings. */
    val tuples: Long,
    /** Charged preparation and enumeration work, included in total polynomial work. */
    val workSpent: Long,
    /** Completed empty residue systems consumed as refutations. */
    val refutedScopes: Long,
    /** Checks declined by private work, storage or representation ceilings. */
    val declinedChecks: Long,
)

internal fun integerResidueStats(
    params: NonlinearIntegerTheoryParams,
    workers: List<NonlinearIntegerInvocation>,
): NonlinearIntegerResidueStats? {
    if (!params.residueRefutation) return null
    return NonlinearIntegerResidueStats(
        workers.sumOf { it.residueCounts.rings }, workers.sumOf { it.residueCounts.tuples },
        workers.sumOf { it.residueCounts.work }, workers.sumOf { it.residueCounts.refuted },
        workers.sumOf { it.residueCounts.declined },
    )
}
