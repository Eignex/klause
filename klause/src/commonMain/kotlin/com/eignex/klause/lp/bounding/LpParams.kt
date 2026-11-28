package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.util.ExecutionControl

/**
 * Parameters of the LP-bounding runtime, which both backtrack search and presolve drive.
 *
 * [lpConfig], when set, is resolved against the problem's structure into [lpPlan] by [LpAutoConfig.resolve];
 * otherwise [lpPlan] is used verbatim.
 */
class LpParams private constructor(
    /** The per-technique relaxation plan, used verbatim when [lpConfig] is null. */
    val lpPlan: LpPlan,
    /** High-level emphasis to resolve into [lpPlan] against the problem structure, or null. */
    val lpConfig: LpConfig?,
    /** Deadline token polled by every LP solve / fixpoint. */
    val cancellation: ExecutionControl,
    /** Whole-solve budget in milliseconds, which sizes the cumulative LP work allowance; null = uncapped. */
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

    /** These params with [lpPlan] replaced. */
    fun copy(lpPlan: LpPlan = this.lpPlan): LpParams =
        LpParams(lpPlan, lpConfig, cancellation, solveBudgetMillis, randomSeed, zeroObjectivePricing,
            componentReuseWorkHorizon)
}

// Lets the engine record LP points for branching hints without depending on the search layer's `LpHints`.
internal interface LpHintSink {
    fun clear()

    // Advisory floating optimum in minimized working units; supplies no certified bound.
    fun recordObjective(relaxation: LpRelaxation, objective: Double) {}

    fun recordInfeasible() {}

    fun record(relaxation: LpRelaxation, primal: DoubleArray, duals: DoubleArray)
}
