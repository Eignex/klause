package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.util.ExecutionControl

/** Which Boolean branching a complete open-model traversal uses. */
enum class OpenBranching(
    /** Identifier accepted by the `open-branching` engine param. */
    val id: String,
) {
    /** Static source order, false before true. */
    SourceOrder("source-order"),

    /** Conflict-activity order over the shared session. */
    Activity("activity"),

    /** Select branching from the complete traversal and its opening. */
    Automatic("auto"),
    ;

    /** Resolution from the identifier the engine param carries. */
    companion object {
        /** The branching named [id], or null when no branching carries that name. */
        fun of(id: String): OpenBranching? = entries.firstOrNull { it.id == id }
    }
}

internal const val DEFAULT_HINT_MIN_SPLITS = 128L

/** Cooperative limits shared by complete open-model theories. */
data class TheoryParams(
    /** Maximum theory-check allowance; this is not a complete leaf count. */
    val maxLeaves: Long = Long.MAX_VALUE,
    /** Solve-wide deterministic open-theory work allowance, covering the hint draw and the traversal. */
    val openWorkLimit: Long = Long.MAX_VALUE,
    /** Maximum committed shared decisions, independent of open-theory work accounting. */
    val maxDecisions: Long = Long.MAX_VALUE,
    /** Explicit shared-decision restart cadence, or null to use the traversal's default. */
    val sharedRestart: Long? = null,
    /** Retained shared learned-clause cap within each feasibility traversal, or null for no cap. */
    val maxLearnedClauses: Int? = null,
    /** LBD at or below which a learned clause is retained across reductions. */
    val lbdGlue: Int = 2,
    /**
     * Local-search allowance for the request's one unverified Boolean hint draw, or null to draw none.
     *
     * A hint buys only branch order and costs work before the first decision. Zero runs the producer under an
     * allowance it cannot reach a proposal within, which separates the producer's cost from the proposal's
     * effect. The setting also gates point and source-clause preferences: null permits direct primitive point
     * preferences for one linear completion owner, zero permits construction observations without publishing a
     * component preference, and a positive value permits indexed preferences after caller or deferred hints
     * decline.
     *
     * A ceiling on the draw, not an allowance beside [openWorkLimit]: the draw spends what that budget has
     * left and is charged there.
     */
    val openHintFlips: Long? = null,
    /**
     * Boolean branching for each feasibility traversal.
     *
     * [OpenBranching.Automatic] selects activity for a complete mixed worker with one default point owner.
     * A complete pure-real worker in the default single-lane feasibility portfolio can switch from source order
     * to activity at a single restart. Other automatic openings use source order. The theory decides the
     * arithmetic residual at a leaf.
     */
    val openBranching: OpenBranching = OpenBranching.Automatic,
    /**
     * Splits that must consult the hint before its allowance is spent drawing one, so the search shows it is
     * branching-bound first. One draws at the first split.
     */
    val openHintMinSplits: Long = DEFAULT_HINT_MIN_SPLITS,
    /** Wall-clock timeout token, kept separate from external cancellation for result reporting. */
    val timeout: ExecutionControl = ExecutionControl.Never,
    /** Cooperative cancellation token. */
    val cancellation: ExecutionControl = ExecutionControl.Never,
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
    /** Caller ceiling on cumulative source LP work, or [Long.MAX_VALUE] for no ceiling. */
    val sourceLpWorkLimit: Long = Long.MAX_VALUE,
    /** Caller ceiling on cumulative modeled source LP allocation in bytes, or [Long.MAX_VALUE] for no ceiling. */
    val sourceLpAllocationLimit: Long = Long.MAX_VALUE,
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
    /** Compatibility field for older callers. Must be false. */
    val sourceRefutationResolution: Boolean = false,
    /** Complete advisory discrete incumbent coordinates before exact companion admission. */
    val sourceIncumbentCompletion: Boolean = false,
    /** Schedule touched linear rows and deliver explained interval consequences before numerical checks. */
    val openLinearIncremental: Boolean = true,
    /** Rank numerical frontier integer splits by installed checked cut supports without proof authority. */
    val sourceCutBranching: Boolean = false,
    /** Move a pre-incumbent joint repair center using paid conditional refutation advice. */
    val sourceRefutationCenterStep: Boolean = false,
) {
    init {
        require(!sourceRefutationResolution) { "source-refutation-resolution has been removed" }
        require(!sourceRefutationCenterStep || sourceIncumbentCompletion) {
            "source-refutation-center-step requires source-incumbent-completion"
        }
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
