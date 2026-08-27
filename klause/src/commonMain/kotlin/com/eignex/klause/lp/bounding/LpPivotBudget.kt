package com.eignex.klause.lp.bounding

/**
 * Decides whether a node LP still gets to run to its optimum, or has to hand back the iterate it has
 * reached at [cap] pivots.
 *
 * The dual simplex is dual-feasible at every basis it passes through, so a stopped solve still carries a
 * valid bound — capping is a throughput trade, never a soundness one. But it is not a free trade: a
 * relaxation that prunes is worth its pivots, and capping one costs bound quality outright. So the
 * budget is aimed only at the profile that measurably does not repay it — an LP that has run
 * [warmupSolves] solves without a single prune. A prune at any point lifts the budget permanently, on
 * the same "a prune spares it" rule [LpEffortGovernor] uses, and for the same reason.
 *
 * Kept off [LpEngine] as a small value so the decision is deterministic and unit-testable;
 * `cap <= 0` disables it, leaving the size-derived budget that on most models never binds.
 */
internal class LpPivotBudget(private val cap: Int, private val warmupSolves: Int) {
    private var solves = 0
    private var everPruned = false

    /**
     * Note one node LP solve: whether it [pruned], and whether it [couldPrune] at all.
     *
     * Only a solve that could have pruned counts toward the warmup. Before the first incumbent there is
     * no bound to prune against, so those solves say nothing about whether the relaxation is any good —
     * counting them lets the window expire on a model that simply took a while to find its first
     * solution, and capping it then is what stops it finding better ones.
     */
    fun observe(pruned: Boolean, couldPrune: Boolean) {
        if (couldPrune) solves++
        if (pruned) everPruned = true
    }

    /** Pivots the next node LP may spend, or 0 for the size-derived budget. */
    fun pivots(): Int = if (cap <= 0 || everPruned || solves < warmupSolves) 0 else cap
}
