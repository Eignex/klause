package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.util.Cancellation

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
    /** The whole-solve wall budget in milliseconds, which sizes the cumulative LP work allowance; null = uncapped. */
    val solveBudgetMillis: Long? = null,
    /** Seed for the feasibility pump and randomized pricing; null means 0. */
    val randomSeed: Long? = null,
    /** Entering-column policy for exactly zero-objective LP solves. */
    val zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
) {
    /** These params with [lpPlan] replaced (the resolved-plan swap [LpEngine] makes after auto-config). */
    fun copy(lpPlan: LpPlan = this.lpPlan): LpParams =
        LpParams(lpPlan, lpConfig, cancellation, solveBudgetMillis, randomSeed, zeroObjectivePricing)
}

internal enum class LpHintUnavailable {
    UNOBSERVED,
    ALLOWANCE,
    DEPTH,
    CADENCE,
    LADDER,
    MODEL,
    SUSPENDED,
    NO_RESULT,
    NO_BOUND,
    CANCELLED,
    EARLIER_BOUND,
    FEASIBILITY_ONLY,
}

/**
 * The LP-solution sink [LpEngine] records each node's fractional primal and reduced costs into. The
 * search-layer branching-hint store (`LpHints`) implements it; presolve passes no sink. Keeping the engine
 * dependent only on this record interface (not on `LpHints`, which reads the backtrack `VarRef` selector
 * type) is what lets the engine live below the search layer.
 */
internal interface LpHintSink {
    fun clear()

    /** Advisory numeric projection of an accepted bound in minimized working units; adds no proof authority. */
    fun recordBound(relaxation: LpRelaxation, bound: Double) {}

    /** Advisory floating optimum when ordinary bounding needs no certificate. */
    fun recordObjective(relaxation: LpRelaxation, objective: Double) {}

    fun recordInfeasible() {}

    fun recordUnavailable(reason: LpHintUnavailable) {}

    /** Record an advisory iterate's [primal] and [duals] against [relaxation]'s column→variable map. */
    fun record(relaxation: LpRelaxation, primal: DoubleArray, duals: DoubleArray)
}
