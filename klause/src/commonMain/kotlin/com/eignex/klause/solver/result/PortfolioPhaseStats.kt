package com.eignex.klause.solver.result

import com.eignex.klause.portfolio.PortfolioPhase

/** Bounded per-phase portfolio accounting. Nested times are included in [nanos], not additive to it. */
data class PortfolioPhaseStats(
    /** Phase at selection, except a segment accepting a terminal source claim closes the proof phase. */
    val phase: PortfolioPhase,
    /** Settled segments. */
    val segments: Long = 0,
    /** Native work, including handle initialization. */
    val work: Long = 0,
    /** Segment nanoseconds through reward settlement, including coordinator checking. */
    val nanos: Long = 0,
    /** Coordinator verification requests, including failed and indeterminate checks. */
    val checks: Long = 0,
    /** Coordinator checking time included in [nanos]. */
    val checkingNanos: Long = 0,
    /** Strictly accepted source incumbents; repeated proposals earn none. */
    val incumbents: Long = 0,
    /** Accepted terminal source claims; these are not independently checked proofs. */
    val closures: Long = 0,
    /** Settled progress and downstream use credit; bounded by the portfolio signal vocabulary. */
    val credit: Map<String, Double> = emptyMap(),
    /** Handle construction time included in [nanos]. */
    val initializationNanos: Long = 0,
    /** Handle construction work included in [work]. */
    val initializationWork: Long = 0,
    /** Segment time with private preparation pending at query selection; included in [nanos]. */
    val preparationNanos: Long = 0,
)

/** Fixed-size lifecycle summary. Timestamps use the containing portfolio invocation's monotonic clock. */
data class PortfolioLifecycleStats(
    /** Factory completion, or zero for workers constructed outside an invocation. */
    val createdNanos: Long = 0,
    /** Factory time before solving, outside segment time. */
    val constructionNanos: Long = 0,
    /** First offered retained search query, including its preparation; null if never queried. */
    val firstQueryNanos: Long? = null,
    /** First accepted pool incumbent while this worker was live. */
    val incumbentAvailableNanos: Long? = null,
    /** First version actually read from the shared incumbent channel. */
    val incumbentReadNanos: Long? = null,
    /** First incumbent accepted into a private companion owner. */
    val incumbentImportedNanos: Long? = null,
    /** First private bound/center consumer after an accepted import. */
    val incumbentConsumedNanos: Long? = null,
    /** Explicit retirement or containing invocation cleanup. */
    val retiredNanos: Long? = null,
    /** Handle disposal time outside settled segment time; included in portfolio invocation time. */
    val retirementNanos: Long = 0,
    /** Shared incumbent versions read; this does not imply acceptance. */
    val incumbentReads: Long = 0,
    /** Private accepted imports. */
    val incumbentImports: Long = 0,
    /** Actual private bound/center consumer calls following an import. */
    val incumbentUses: Long = 0,
    /** Inclusive lane time, including reward/observer overhead and in-lane handle retirement. */
    val activeNanos: Long = 0,
)
