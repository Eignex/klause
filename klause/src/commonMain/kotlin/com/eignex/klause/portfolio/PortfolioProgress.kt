package com.eignex.klause.portfolio

/** The accepted source progress a portfolio is pursuing or has closed. */
enum class PortfolioPhase {
    /** Search before the first accepted source witness. */
    FEASIBILITY,
    /** Search with an accepted source incumbent. */
    IMPROVEMENT,
    /** An accepted terminal source claim; native authority is not independent checking. */
    PROOF_CLOSURE,
}

/** A settled segment, supplied to an optional reward policy without changing coverage or admission. */
data class PortfolioProgress(
    /** Stable worker slot. */
    val arm: Int,
    /** Phase at segment selection, or accepted terminal closure. */
    val phase: PortfolioPhase,
    /** Native work, including handle initialization. */
    val work: Long,
    /** Inclusive segment time, including coordinator checking, in nanoseconds. */
    val nanos: Long,
    /** Coordinator verification requests, including rejected and indeterminate claims. */
    val checks: Long,
    /** Accepted first witness or strict incumbent improvement in this segment. */
    val acceptedIncumbent: Boolean,
    /** Whether the segment failed. */
    val failed: Boolean,
    /** Settled contribution deltas; creation counters carry no source acceptance authority. */
    val credit: Map<String, Double>,
    /** Reward from the ordinary rate ledger, including downstream sharing use. */
    val ordinaryReward: Double,
    /** Number of accepted strict incumbents; multiple proposals can settle in one segment. */
    val acceptedIncumbents: Long = 0,
    /** A source claim was refuted, distinct from an ordinary engine exception. */
    val refuted: Boolean = false,
    /** Coordinator verification time, included in [nanos]. */
    val checkingNanos: Long = 0,
    /** Handle construction time, included in [nanos]. */
    val initializationNanos: Long = 0,
    /** Handle construction work, included in [work]. */
    val initializationWork: Long = 0,
    /** Factory cost before the first segment; zero on subsequent segments. */
    val constructionNanos: Long = 0,
    /** Private preparation was pending at query selection. Its cost is included in [nanos] and [work]. */
    val preparing: Boolean = false,
)

/** Opt-in reward experiment. Results must be finite and in `[0, 1]`; coverage and family priors remain intact. */
fun interface PortfolioRewardPolicy {
    /** Score a settled segment. Failed segments always receive zero after this call. */
    fun reward(progress: PortfolioProgress): Double
}

/** Concrete lifecycle observations; availability, read, accepted import and consumption are distinct. */
enum class PortfolioLifecycleKind {
    /** Worker factory completed. */
    CREATED,
    /** First query offered to the retained engine, including preparation. */
    FIRST_QUERY,
    /** A source-accepted incumbent exists while the worker is live. */
    INCUMBENT_AVAILABLE,
    /** Worker read a shared incumbent version. */
    INCUMBENT_READ,
    /** Worker accepted a shared incumbent into its private state. */
    INCUMBENT_IMPORTED,
    /** A private bound or center consumer used an accepted imported incumbent. */
    INCUMBENT_CONSUMED,
    /** Worker retired or its containing invocation ended. */
    RETIRED,
}
