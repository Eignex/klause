package com.eignex.klause.lp.bounding

/**
 * Adaptive work budget for a node LP: how much a solve may spend, adjusted from what the last one did.
 *
 * The signal is the solve itself, not the search around it. Two facts are available every time and need
 * no incumbent: whether the solve reached its optimum, and how dually degenerate the basis it stopped on
 * was. Budgeting on prune counts instead cannot work, because a relaxation that has pruned nothing is
 * indistinguishable from one whose search has not yet produced a bound to prune against — the two want
 * opposite treatment, and the models with the most to gain from a budget are exactly the ones that never
 * find an incumbent.
 *
 * So:
 *  - **stopped short, not degenerate** — the budget was the binding constraint and the solve was making
 *    progress, so double it;
 *  - **stopped short, degenerate** — it was stalling among tied columns, so shrink it;
 *  - **reached the optimum, not degenerate** — the model's own size predicts the cost better than any
 *    history, so reset to the size baseline the caller supplies;
 *  - **reached the optimum, degenerate** — shrink harder still.
 *
 * Bounded by [minOps] and [maxOps]: the floor keeps a weak bound coming rather than switching the LP off,
 * which with a persistent basis is never wasted — a stopped solve is progress the next node resumes from.
 *
 * Kept off [LpEngine] as a small value so the decision is deterministic and unit-testable. Mirrors
 * CP-SAT's `UpdateSimplexIterationLimit`, in work rather than iterations because a pivot is not a unit
 * of cost.
 */
internal class LpWorkBudget(private val minOps: Long, private val maxOps: Long, initialOps: Long) {
    private var next: Long = initialOps.coerceIn(minOps, maxOps)

    /** Work the next node LP may spend. */
    fun ops(): Long = next

    /**
     * Fold in one solve: whether it [reachedOptimum], its [degenerateColumns] out of [columns], and the
     * [sizeBudget] the model's own size implies. A solve that produced no result at all (infeasible, or a
     * numerical bail) says nothing about the budget and is not fed back.
     */
    fun observe(reachedOptimum: Boolean, degenerateColumns: Int, columns: Int, sizeBudget: Long) {
        if (columns <= 0) return
        val degenerate = degenerateColumns >= DEGENERATE_SHARE * columns
        // How hard to shrink: proportional to the share of columns that are tied, so a mildly degenerate
        // basis is barely punished and a wholly degenerate one is cut hard.
        val shrink = (SHRINK_SCALE * degenerateColumns) / columns
        next = when {
            !reachedOptimum && !degenerate -> next * 2
            !reachedOptimum -> next / maxOf(1, shrink)
            !degenerate -> sizeBudget
            else -> next / maxOf(1, 2 * shrink)
        }
        next = next.coerceIn(minOps, maxOps)
    }

    companion object {
        /** Share of tied columns past which a basis counts as degenerate. */
        const val DEGENERATE_SHARE: Double = 0.3

        /** Numerator of the shrink factor `SHRINK_SCALE × degenerate / columns`. */
        const val SHRINK_SCALE: Int = 10
    }
}
