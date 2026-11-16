package com.eignex.klause.solver.pipeline

/** Invocation-wide observations of bounded integer residue evaluation. */
data class NonlinearIntegerResidueStats(
    /** Rings with a support or a completed image or tuple refutation. */
    val completedRings: Long,
    /** Shared-coordinate residue tuples evaluated, including unfinished rings. */
    val tuples: Long,
    /** Completed residue steps, included in the containing polynomial work counter. */
    val workSpent: Long,
    /** Completed empty residue systems or Boolean coverage consumed as source refutations. */
    val refutedScopes: Long,
    /** Checks declined for unsupported source support or private work, storage and representation ceilings. */
    val declinedChecks: Long,
    /** Globally impossible equality atoms replaced by false in NIA-owned completion formulas. */
    val excludedAtoms: Long = 0,
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
        workers.sumOf { it.residueCounts.excluded },
    )
}
