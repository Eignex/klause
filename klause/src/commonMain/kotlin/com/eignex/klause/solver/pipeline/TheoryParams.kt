package com.eignex.klause.solver.pipeline

import com.eignex.klause.util.Cancellation

/** Which Boolean branching a complete open-model traversal uses. */
enum class OpenBranching(
    /** Identifier accepted by the `open-branching` engine param. */
    val id: String,
) {
    /** Static source order, false before true. */
    SourceOrder("source-order"),

    /** Conflict-activity order over the shared session. */
    Activity("activity"),
    ;

    /** Resolution from the identifier the engine param carries. */
    companion object {
        /** The branching named [id], or null when no branching carries that name. */
        fun of(id: String): OpenBranching? = entries.firstOrNull { it.id == id }
    }
}

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
    /**
     * Boolean branching for each feasibility traversal.
     *
     * Only the Boolean skeleton is branched here, so this is the whole variable-order lever the open
     * route has; the theory decides the arithmetic residual at a leaf either way. Source order is the
     * default because activity order settles no additional instance on the measured corpus: it moves
     * the work a traversal does without moving the verdict, since the rows that remain open are
     * bounded by the cost of each theory check rather than by the branch that reached it.
     */
    val openBranching: OpenBranching = OpenBranching.SourceOrder,
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
