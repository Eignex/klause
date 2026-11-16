package com.eignex.klause.solver.result

import kotlin.time.Duration

/** Presolve representation in which an invocation ran. */
enum class PresolveLane {
    /** Declared source bounds and factors. */
    SOURCE,
    /** Finite root domains, including construction and external stages. */
    FINITE,
}

/** Whether analysis was inapplicable, completed, productive or stopped. */
enum class PresolveInvocationOutcome {
    /** An applicability gate declined the reduction analysis. */
    ELIGIBILITY_SKIP,
    /** Analysis completed without a reduction. */
    COMPLETE_MISS,
    /** Completed reductions were retained. */
    CHANGED,
    /** Analysis proved infeasibility. */
    REFUTED,
    /** Analysis stopped; separately completed reductions can remain sound. */
    INTERRUPTED,
    /** A preparation operation completed. */
    COMPLETE,
}

/** Constant-time dimensions; columns include reconstructed or unused declarations. */
class PresolveSizeStats internal constructor(
    /** Factors in the model. */
    val factors: Int,
    /** Declared Boolean columns. */
    val booleans: Int,
    /** Declared integer columns. */
    val integers: Int,
    /** Variable occurrences when entry analysis already computed them; null for cheap skips. */
    val occurrences: Long? = null,
    /** Declared real columns. */
    val reals: Int = 0,
)

/** One attempted presolve analysis, including empty and interrupted calls. */
class PresolveInvocationStats internal constructor(
    /** Serializable pass id. */
    val pass: String,
    /** Input representation. */
    val lane: PresolveLane,
    /** Analysis completion status, independent of its yield. */
    val outcome: PresolveInvocationOutcome,
    /** Active elapsed time, excluding nested observed stages. */
    val elapsed: Duration,
    /** Phase work, excluding nested observed stages. */
    val work: Long,
    /** Construction and propagation work included in [work]. */
    val rebuildWork: Long,
    /** Dimensions at entry. */
    val input: PresolveSizeStats,
    /** Dimensions after retaining completed reductions. */
    val output: PresolveSizeStats,
    /** Integer columns explicitly narrowed by the pass. */
    val narrowedColumns: Int = 0,
    /** Distinct new integer or Boolean fixings retained by the pass or construction tier. */
    val fixedColumns: Int = 0,
    /** Native visits outside the phase currency; existing containing charges are forwarded unchanged. */
    val nativeWork: Long = 0L,
    /** Bounded rewrite counts; empty when disabled, Complete=0 for an incomplete view. */
    val structuralImpact: Map<String, Long> = emptyMap(),
)
