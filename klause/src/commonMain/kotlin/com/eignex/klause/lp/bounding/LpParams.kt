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
class LpParams(
    /** The per-technique relaxation plan, used verbatim when [lpConfig] is null. */
    val lpPlan: LpPlan = LpPlan(),
    /** High-level emphasis to resolve into [lpPlan] against the problem structure, or null. */
    val lpConfig: LpConfig? = null,
    /** Deadline token polled by every LP solve / fixpoint. */
    val cancellation: ExecutionControl = ExecutionControl.Never,
    /** Whole-solve budget in milliseconds, which sizes the cumulative LP work allowance; null = uncapped. */
    val solveBudgetMillis: Long? = null,
    /** Seed for the feasibility pump and randomized pricing; null means 0. */
    val randomSeed: Long? = null,
    /** Entering-column policy for exactly zero-objective LP solves. */
    val zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
) {
    /** These params with [lpPlan] replaced. */
    fun copy(lpPlan: LpPlan = this.lpPlan): LpParams =
        LpParams(lpPlan, lpConfig, cancellation, solveBudgetMillis, randomSeed, zeroObjectivePricing)
}

// Lets the engine record LP points for branching hints without depending on the search layer's `LpHints`.
internal interface LpHintSink {
    fun clear()

    // Advisory floating optimum in minimized working units; supplies no certified bound.
    fun recordObjective(relaxation: LpRelaxation, objective: Double) {}

    fun recordInfeasible() {}

    fun record(relaxation: LpRelaxation, primal: DoubleArray, duals: DoubleArray)
}
