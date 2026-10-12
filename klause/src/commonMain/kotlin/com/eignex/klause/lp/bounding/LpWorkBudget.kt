package com.eignex.klause.lp.bounding

/**
 * Adaptive per-node LP work budget, adjusted from solve completion and dual degeneracy:
 *  - Incomplete, nondegenerate solves double the budget.
 *  - Incomplete, degenerate solves shrink it.
 *  - Optimal, nondegenerate solves reset it to the caller's size baseline.
 *  - Optimal, degenerate solves shrink it further.
 *
 * [minOps] and [maxOps] bound the budget. A persistent basis retains partial progress between
 * solves. Prune counts are unsuitable feedback because pruning also depends on the incumbent.
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
