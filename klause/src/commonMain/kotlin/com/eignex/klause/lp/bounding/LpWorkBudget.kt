package com.eignex.klause.lp.bounding

// Adapts from solve completion and dual degeneracy rather than prune counts, because pruning also depends
// on the incumbent.
internal class LpWorkBudget(private val minOps: Long, private val maxOps: Long, initialOps: Long) {
    private var next: Long = initialOps.coerceIn(minOps, maxOps)

    fun ops(): Long = next

    fun observe(reachedOptimum: Boolean, degenerateColumns: Int, columns: Int, sizeBudget: Long) {
        if (columns <= 0) return
        val degenerate = degenerateColumns >= DEGENERATE_SHARE * columns
        // Proportional to the tied share, so a mildly degenerate basis is barely punished.
        val shrink = (SHRINK_SCALE * degenerateColumns) / columns
        next = when {
            !reachedOptimum && !degenerate -> next * 2
            !reachedOptimum -> next / maxOf(1, shrink)
            !degenerate -> sizeBudget
            else -> next / maxOf(1, 2 * shrink)
        }
        next = next.coerceIn(minOps, maxOps)
    }

    fun observeWorkStop() {
        next += minOf(next, maxOps - next)
    }

    companion object {
        const val DEGENERATE_SHARE: Double = 0.3

        const val SHRINK_SCALE: Int = 10
    }
}
