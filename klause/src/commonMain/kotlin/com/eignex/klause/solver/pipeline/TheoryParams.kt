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

/**
 * Splits a hint waits for by default before it is drawn.
 *
 * Chosen from the gap in the measured corpus rather than tuned: the instances that branch reach
 * thousands of splits, the ones the theory dominates reach tens, so anything inside that gap separates
 * them and the exact value is not load-bearing.
 */
private const val DEFAULT_HINT_MIN_SPLITS = 128L

/** Cooperative limits shared by complete open-model theories. */
data class TheoryParams(
    /** Legacy maximum theory-check allowance; this is not a complete leaf count. */
    val maxLeaves: Long = Long.MAX_VALUE,
    /** Solve-wide deterministic open-theory work allowance, covering the hint draw and the traversal. */
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
     *
     * This is a ceiling on the draw, not an allowance beside [openWorkLimit]: the draw may spend only
     * what that budget has left and what it spends is charged there, so the traversal of a hinted run
     * gets the budget minus the draw.
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
    /**
     * Splits that must consult the hint before its allowance is spent drawing one.
     *
     * A model whose Boolean columns propagation settles reaches almost no split, so a hint drawn up
     * front there costs its whole allowance where no branch order could have paid for it. Waiting makes
     * the search show it is branching-bound first. One means draw at the first split; a threshold past
     * any split the search reaches means never draw at all.
     */
    val openHintMinSplits: Long = DEFAULT_HINT_MIN_SPLITS,
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
        require(openHintMinSplits >= 1) { "hint split threshold must be positive" }
    }
}
