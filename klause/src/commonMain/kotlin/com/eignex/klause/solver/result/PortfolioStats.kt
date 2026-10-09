package com.eignex.klause.solver.result

import com.eignex.klause.solver.ProblemProfile

/**
 * What a sequential portfolio scheduled, one [ArmSchedule] per arm: how often it ran, what that cost, and what
 * the scheduler credited it for. Empty for any other solve. See [SolveStats].
 */
data class PortfolioStats(
    /** Per-arm schedule, in the portfolio's arm order. */
    val arms: List<ArmSchedule> = emptyList(),
    /** The classification the portfolio was built for; null when it was given none. */
    val profile: ProblemProfile? = null,
    /** Effective reseeding threshold; null when no executor supplied one. */
    val reseedStaleThreshold: Int? = null,
) {
    /** Combine two runs' schedules: the arms of both, in order, under the first one's classification. */
    fun mergedWith(o: PortfolioStats): PortfolioStats = PortfolioStats(
        arms + o.arms, profile ?: o.profile, reseedStaleThreshold ?: o.reseedStaleThreshold,
    )
}

/** One arm of a sequential portfolio's schedule; see [PortfolioStats]. */
data class ArmSchedule(
    /** The arm's worker label. */
    val label: String,
    /** Segments the arm ran. */
    val segments: Long,
    /** Work its segments spent, in node-equivalents. */
    val work: Long,
    /** Wall-clock milliseconds its segments took, so time and work can be compared. */
    val millis: Long = 0L,
    /** Mean reward its segments settled for. */
    val meanReward: Double,
    /** Segments that failed with an exception. */
    val failures: Long,
    /** Claims the model refuted; one quarantines the arm. */
    val faults: Long = 0L,
    /** Credit the arm earned, by the kind of contribution that earned it. */
    val credit: Map<String, Double>,
    /** Longest segment, including handle construction and root setup. */
    val maxMillis: Long = 0L,
    /** Milliseconds constructing resumable handles, included in [millis]. */
    val initializationMillis: Long = 0L,
    /** Resumable handles discarded after an incumbent plateau. */
    val reseeds: Long = 0L,
    /** What the arm's sharing with the rest of the pool cost and moved, included in [millis]. */
    val sharing: SharingStats = SharingStats(),
    /** Work constructing resumable handles, including interrupted openings; included in [work]. */
    val initializationWork: Long = 0L,
    /** Openings retired because construction was cancelled. */
    val initializationCancelled: Long = 0L,
)
