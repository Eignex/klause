package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.solver.Cancellation

/**
 * The LP-bounding runtime's own parameters, independent of the search layer's `BacktrackParams`. Both the
 * backtrack search (per-node bounding) and presolve (the LP harvest) drive [LpEngine], so its inputs are
 * expressed here in the LP layer rather than pulled from the backtrack params object.
 *
 * [lpConfig], when set, is a high-level emphasis resolved against the problem's structure into a concrete
 * [lpPlan] by [LpAutoConfig.resolve]; with no emphasis the [lpPlan] is used verbatim.
 */
class LpParams(
    /** The per-technique relaxation plan, used verbatim unless [lpConfig] resolves a richer one. */
    val lpPlan: LpPlan = LpPlan(),
    /** High-level emphasis to resolve into [lpPlan] against the problem structure, or null. */
    val lpConfig: LpConfig? = null,
    /** Deadline token polled by every LP solve / fixpoint. */
    val cancellation: Cancellation = Cancellation.Never,
    /** The whole-solve wall budget in milliseconds, used to bound cumulative LP time; null = uncapped. */
    val solveBudgetMillis: Long? = null,
    /** Seed for the feasibility-pump RNG; null picks a fixed default. */
    val randomSeed: Long? = null,
) {
    /** These params with [lpPlan] replaced (the resolved-plan swap [LpEngine] makes after auto-config). */
    fun copy(lpPlan: LpPlan = this.lpPlan): LpParams =
        LpParams(lpPlan, lpConfig, cancellation, solveBudgetMillis, randomSeed)
}

/**
 * The LP-solution sink [LpEngine] records each node's fractional primal and reduced costs into. The
 * search-layer branching-hint store (`LpHints`) implements it; presolve passes no sink. Keeping the engine
 * dependent only on this record interface (not on `LpHints`, which reads the backtrack `VarRef` selector
 * type) is what lets the engine live below the search layer.
 */
internal interface LpHintSink {
    /** Record an LP solution's [primal] and [duals] against [relaxation]'s column→variable map. */
    fun record(relaxation: LpRelaxation, primal: DoubleArray, duals: DoubleArray)
}
