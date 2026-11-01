package com.eignex.klause.solver.result

/**
 * One requested Boolean proof-sharing attempt. An eligible whole-source probe can finish or stop
 * before creating tasks. These counters confer no proof authority or independent verification.
 * Offered-work reservations and coordinator checking remain in the ordinary portfolio schedule.
 */
data class BooleanProofStats(
    /** Regions in the partition; zero when probing did not create a board. */
    val tasks: Int,
    /** Task regions closed by native refutations, without independent proof checking. */
    val nativeRefutations: Int,
    /** Whole-source probe segments, including interrupted preparation. */
    val probeSegments: Int,
    /** Worker work spent probing; a subset of [workerWork], never an additional charge. */
    val probeWork: Long,
    /** Cumulative engine work, including probes, task queries and retired attempts. */
    val workerWork: Long,
    /** Diagnostic reason the model, workers or schedule declined sharing; null for admitted probing. */
    val decline: String? = null,
    /** Paused probes continued from their retained conditioned root, without reseeding. */
    val retainedProbes: Int = 0,
)
