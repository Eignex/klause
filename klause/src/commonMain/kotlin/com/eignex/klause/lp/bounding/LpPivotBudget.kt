package com.eignex.klause.lp.bounding

// Caps a node LP at `cap` pivots once warmupSolves prunable solves pass without a prune. The dual simplex
// stays dual-feasible, so capping trades bound quality, never soundness. A prune lifts the cap and restarts
// the window rather than latching, so a relaxation that pruned once early can still be capped later.
// `cap <= 0` disables it.
internal class LpPivotBudget(private val cap: Int, private val warmupSolves: Int) {
    private var solves = 0

    // Solves before the first incumbent have nothing to prune against, so they do not count toward the
    // warmup; otherwise a model slow to find its first solution would be capped before it finds better ones.
    fun observe(pruned: Boolean, couldPrune: Boolean) {
        if (pruned) {
            solves = 0
            return
        }
        if (couldPrune) solves++
    }

    // 0 means the size-derived budget.
    fun pivots(): Int = if (cap <= 0 || solves < warmupSolves) 0 else cap
}
