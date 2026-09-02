package com.eignex.klause.solver.pipeline

import com.eignex.klause.util.Cancellation

/** Cooperative limits shared by complete open-model theories. */
data class TheoryParams(
    /** Legacy maximum theory-check allowance; this is not a complete leaf count. */
    val maxLeaves: Long = Long.MAX_VALUE,
    /** Solve-wide deterministic open-theory work allowance. */
    val openWorkLimit: Long = Long.MAX_VALUE,
    /** Maximum committed shared decisions, independent of open-theory work accounting. */
    val maxDecisions: Long = Long.MAX_VALUE,
    /** Positive shared-decision restart cadence within each feasibility traversal, or null for none. */
    val sharedRestart: Long? = null,
    /** Retained shared learned-clause cap within each feasibility traversal, or null for no cap. */
    val maxLearnedClauses: Int? = null,
    /** LBD at or below which a learned clause is retained across reductions. */
    val lbdGlue: Int = 2,
    /**
     * Local-search allowance for the request's one unverified Boolean hint draw, or null to draw none.
     *
     * Null by default. A hint buys nothing but branch order, and buying it costs work before the first
     * decision, so whether it pays is a measured question rather than a default. Zero is not that
     * switch: it runs the producer under an allowance it cannot reach a proposal within, which is what
     * separates the producer's own cost from the proposal's effect.
     */
    val openHintFlips: Long? = null,
    /** Wall-clock timeout token, kept separate from external cancellation for result reporting. */
    val timeout: Cancellation = Cancellation.Never,
    /** Cooperative cancellation token. */
    val cancellation: Cancellation = Cancellation.Never,
) {
    init {
        require(maxDecisions >= 0) { "maximum decisions must not be negative" }
        require(sharedRestart == null || sharedRestart > 0) { "shared restart must be positive" }
        require(maxLearnedClauses == null || maxLearnedClauses >= 0) { "learned clause cap must not be negative" }
        require(lbdGlue >= 0) { "glue threshold must not be negative" }
        require(openHintFlips == null || openHintFlips >= 0) { "hint allowance must not be negative" }
    }
}
