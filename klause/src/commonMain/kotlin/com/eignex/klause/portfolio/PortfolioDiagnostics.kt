package com.eignex.klause.portfolio

import com.eignex.klause.solver.result.SharingChannel

/**
 * Opt-in single-lane scheduling by charged work. [workLimit] bounds the complete invocation;
 * atomic preparation and verification can overspend. [Portfolio.thompson] uses [seed] for scheduler
 * choices independently of worker seeds. [onEvent] streams events without retaining a trace in the solver.
 * An external cancellation is an emergency stop, with no deadline exposed to worker policy.
 */
class PortfolioDiagnostics(
    /** Containing work limit, including reserved charges for zero-work returns. */
    val workLimit: Long,
    /** Scheduler random seed. */
    val seed: Long = 0L,
    /** Inline sink; exceptions terminate the invocation. */
    val onEvent: (PortfolioEvent) -> Unit = {},
) {
    init { require(workLimit > 0L) { "diagnostic work limit must be positive" } }
}

/** Logical portfolio events in execution order; physical timing belongs to ordinary statistics. */
sealed interface PortfolioEvent {
    /** A fresh invocation and its scheduler seed and containing work allowance. */
    data class Started(val seed: Long, val workLimit: Long) : PortfolioEvent

    /** One selected worker, its stable slot and offered work. */
    data class Selected(val segment: Long, val arm: Int, val worker: String, val allowance: Long) : PortfolioEvent

    /** A pool synchronization, including empty drains; [cursor] includes the pool generation when present. */
    data class Imported(val arm: Int, val channel: SharingChannel, val cursor: Long, val count: Int) : PortfolioEvent

    /** One actual bandit update, including credit settled while another worker ran. */
    data class Rewarded(val arm: Int, val reward: Double, val weight: Double) : PortfolioEvent

    /** Completed segment charge, including initialization, with an explicit zero-work fallback charge. */
    data class Charged(
        val segment: Long,
        val arm: Int,
        val work: Long,
        val checks: Long,
        val charge: Long,
        val failed: Boolean,
    ) : PortfolioEvent

    /** A worker leaves the live schedule. */
    data class Retired(val arm: Int) : PortfolioEvent

    /** A retained worker discards its handle before another scheduled opening. */
    data class Reseeded(val arm: Int) : PortfolioEvent

    /** Invocation termination: completed, work-limit, external-stop, retired or failure. */
    data class Finished(val reason: String, val chargedWork: Long) : PortfolioEvent
}
