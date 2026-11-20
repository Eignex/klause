package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.util.ExecutionControl

/**
 * The LP-bounding runtime's own parameters, independent of the search layer's `BacktrackParams`. Both the
 * backtrack search (per-node bounding) and presolve (the LP harvest) drive [LpEngine], so its inputs are
 * expressed here in the LP layer rather than pulled from the backtrack params object.
 *
 * [lpConfig], when set, is a high-level emphasis resolved against the problem's structure into a concrete
 * [lpPlan] by [LpAutoConfig.resolve]; with no emphasis the [lpPlan] is used verbatim.
 */
class LpParams private constructor(
    /** The per-technique relaxation plan, used verbatim unless [lpConfig] resolves a richer one. */
    val lpPlan: LpPlan,
    /** High-level emphasis to resolve into [lpPlan] against the problem structure, or null. */
    val lpConfig: LpConfig?,
    /** Deadline token polled by every LP solve / fixpoint. */
    val cancellation: ExecutionControl,
    /** The whole-solve wall budget in milliseconds, which sizes the cumulative LP work allowance; null = uncapped. */
    val solveBudgetMillis: Long?,
    /** Seed for the feasibility pump and randomized pricing; null means 0. */
    val randomSeed: Long?,
    /** Entering-column policy for exactly zero-objective LP solves. */
    val zeroObjectivePricing: LpZeroObjectivePricing,
    internal val componentReuseWorkHorizon: Long?,
) {
    /** Create LP runtime parameters. */
    constructor(
        lpPlan: LpPlan = LpPlan(),
        lpConfig: LpConfig? = null,
        cancellation: ExecutionControl = ExecutionControl.Never,
        solveBudgetMillis: Long? = null,
        randomSeed: Long? = null,
        zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    ) : this(lpPlan, lpConfig, cancellation, solveBudgetMillis, randomSeed, zeroObjectivePricing, null)

    /** Create default LP runtime parameters. */
    constructor() : this(LpPlan(), null, ExecutionControl.Never, null, null,
        LpZeroObjectivePricing.MIN_BOUND_SUPPORT, null)

    internal fun withComponentReuseWorkHorizon(work: Long?): LpParams {
        require(work == null || work >= 0L)
        return LpParams(lpPlan, lpConfig, cancellation, solveBudgetMillis, randomSeed, zeroObjectivePricing, work)
    }

    /** These params with [lpPlan] replaced (the resolved-plan swap [LpEngine] makes after auto-config). */
    fun copy(lpPlan: LpPlan = this.lpPlan): LpParams =
        LpParams(lpPlan, lpConfig, cancellation, solveBudgetMillis, randomSeed, zeroObjectivePricing,
            componentReuseWorkHorizon)
}

/**
 * The LP-solution sink [LpEngine] records each node's fractional primal and reduced costs into. The
 * search-layer branching-hint store (`LpHints`) implements it; presolve passes no sink. Keeping the engine
 * dependent only on this record interface (not on `LpHints`, which reads the backtrack `VarRef` selector
 * type) is what lets the engine live below the search layer.
 */
internal interface LpHintSink {
    fun clear()

    /** Advisory floating optimum in minimized working units; supplies no certified bound. */
    fun recordObjective(relaxation: LpRelaxation, objective: Double) {}

    fun recordInfeasible() {}

    /** Record an LP solution's [primal] and [duals] against [relaxation]'s column→variable map. */
    fun record(relaxation: LpRelaxation, primal: DoubleArray, duals: DoubleArray)
}
