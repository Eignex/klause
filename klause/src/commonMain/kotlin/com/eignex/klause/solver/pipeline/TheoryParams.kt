package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
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

    /** Activity for a complete mixed default point owner; source order for other openings. */
    Automatic("auto"),
    ;

    /** Resolution from the identifier the engine param carries. */
    companion object {
        /** The branching named [id], or null when no branching carries that name. */
        fun of(id: String): OpenBranching? = entries.firstOrNull { it.id == id }
    }
}

private const val DEFAULT_HINT_MIN_SPLITS = 128L

/** Cooperative limits shared by complete open-model theories. */
data class TheoryParams(
    /** Maximum theory-check allowance; this is not a complete leaf count. */
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
     * Null by default. A hint buys only branch order and costs work before the first decision. Zero is not that
     * switch: it runs the producer under an allowance it cannot reach a proposal within, which is what
     * separates the producer's own cost from the proposal's effect.
     *
     * A null allowance permits direct primitive point preferences for one linear completion owner
     * with source integer or real coordinates, without a source-clause index
     * or Boolean draw. A Boolean-complete mixed worker can also order the current source
     * disjunction's rows or a disequality's sides from an existing exact point or optimal float guess;
     * both alternatives remain searched.
     * Other built-in linear point guidance and compound source-clause preferences use
     * this opt-in. Zero permits bounded construction observations
     * but publishes no component preference; a positive value permits indexed preferences after caller
     * or deferred hints decline. Construction and arithmetic comparisons keep their own owners and
     * limits; this flip ceiling supplies no separate allowance for them.
     *
     * This is a ceiling on the draw, not an allowance beside [openWorkLimit]: the draw may spend only
     * what that budget has left and what it spends is charged there, so the traversal of a hinted run
     * gets the budget minus the draw.
     */
    val openHintFlips: Long? = null,
    /**
     * Boolean branching for each feasibility traversal.
     *
     * [OpenBranching.Automatic] selects activity for a complete mixed worker with one default point owner,
     * and source order for other openings, including private queries and explicit hint allowances.
     * The theory decides the arithmetic residual at a leaf.
     */
    val openBranching: OpenBranching = OpenBranching.Automatic,
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
    /** Solve-wide allowance shared by committed open decisions and heuristic moves, or null for none. */
    val nodeBudget: NodeBudget? = null,
    /** Destroy/repair proposal cap for an explicit open ALNS invocation. */
    val alnsIterations: Int = 50,
    /** Seed for exact-coordinate heuristic proposals. */
    val randomSeed: Long = 0,
    /** Solve-wide heuristic proposal cap. */
    val maxFlips: Long = Long.MAX_VALUE,
    /** Solve-wide heuristic instruction cap, including completion work. */
    val maxInstructions: Long? = null,
    /** Cumulative source LP preparation, numerical and certification work, including admission overhead. */
    val sourceLpWorkLimit: Long = 1_000_000_000L,
    /** Cumulative source preparation, proof replay and modeled retained coverage allocation in bytes. */
    val sourceLpAllocationLimit: Long = 512L * 1024L * 1024L,
    /** Optional source LP operation ceiling, independent of cumulative numerical work. */
    val sourceLpOperations: Int = Int.MAX_VALUE,
    /** Enable checked source-row separation for numerical frontier traversal. */
    val sourceCuts: Boolean = true,
    /** Select checked source cuts at completed numerical frontier points. */
    val sourcePointCuts: Boolean = false,
    /** Enable bounded source-bound aggregation for numerical frontier traversal. */
    val sourceAggregateCuts: Boolean = false,
    /** Reuse checked refutations within one numerical traversal under their proved source restrictions. */
    val sourceRefutationReuse: Boolean = false,
    /** Imply integral source bounds from checked refutations at unit prefixes in numerical traversal. */
    val sourceRefutationPropagation: Boolean = false,
    /** Resolve checked refutations across named integral source predicates within numerical traversal. */
    val sourceRefutationResolution: Boolean = false,
    /** Complete advisory discrete incumbent coordinates before exact companion admission. */
    val sourceIncumbentCompletion: Boolean = false,
) {
    init {
        require(sourceLpWorkLimit >= 0 && sourceLpAllocationLimit >= 0 && sourceLpOperations >= 0)
        require(maxFlips >= 0) { "heuristic proposal cap must not be negative" }
        require(maxInstructions == null || maxInstructions >= 0) { "heuristic instruction cap must not be negative" }
        require(alnsIterations >= 0) { "ALNS iteration cap must not be negative" }
        require(maxDecisions >= 0) { "maximum decisions must not be negative" }
        require(sharedRestart == null || sharedRestart > 0) { "shared restart must be positive" }
        require(maxLearnedClauses == null || maxLearnedClauses >= 0) { "learned clause cap must not be negative" }
        require(lbdGlue >= 0) { "glue threshold must not be negative" }
        require(openHintFlips == null || openHintFlips >= 0) { "hint allowance must not be negative" }
        require(openHintMinSplits >= 1) { "hint split threshold must be positive" }
    }
}
