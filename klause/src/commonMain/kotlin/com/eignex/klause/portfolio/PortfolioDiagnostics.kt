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
    data class Started(
        /** Scheduler seed. */
        val seed: Long,
        /** Containing charged-work limit. */
        val workLimit: Long,
    ) : PortfolioEvent

    /** One selected worker, its stable slot and offered work. */
    data class Selected(
        /** Zero-based segment index. */
        val segment: Long,
        /** Stable worker slot. */
        val arm: Int,
        /** Worker recipe label. */
        val worker: String,
        /** Offered node-equivalent work. */
        val allowance: Long,
    ) : PortfolioEvent

    /** A pool synchronization, including empty drains; [cursor] includes the pool generation when present. */
    data class Imported(
        /** Consuming worker slot. */
        val arm: Int,
        /** Shared channel read. */
        val channel: SharingChannel,
        /** Pool cursor, accepted version or supplied floor bits, according to the channel. */
        val cursor: Long,
        /** Imported entries; zero for an empty drain. */
        val count: Int,
    ) : PortfolioEvent

    /** One actual bandit update, including credit settled while another worker ran. */
    data class Rewarded(
        /** Credited worker slot. */
        val arm: Int,
        /** Reward passed to the policy. */
        val reward: Double,
        /** Observation weight passed to the policy. */
        val weight: Double,
    ) : PortfolioEvent

    /** Completed segment charge, including initialization, with an explicit zero-work fallback charge. */
    data class Charged(
        /** Settled segment index. */
        val segment: Long,
        /** Producing worker slot. */
        val arm: Int,
        /** Measured native work in node equivalents. */
        val work: Long,
        /** Coordinator verification requests charged at one unit each. */
        val checks: Long,
        /** Total containing charge, including any zero-work reservation and checks. */
        val charge: Long,
        /** Whether the segment threw an ordinary failure. */
        val failed: Boolean,
    ) : PortfolioEvent

    /** A worker leaves the live schedule. */
    data class Retired(/** Retired worker slot. */ val arm: Int) : PortfolioEvent

    /** A retained worker discards its handle before another scheduled opening. */
    data class Reseeded(/** Reseeded worker slot. */ val arm: Int) : PortfolioEvent

    /** Invocation termination: completed, work-limit, external-stop, retired or failure. */
    data class Finished(
        /** Logical termination cause. */
        val reason: String,
        /** Total charged node equivalents. */
        val chargedWork: Long,
    ) : PortfolioEvent
}
