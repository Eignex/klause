package com.eignex.klause.solver.lp.cut

import kotlin.math.abs

/**
 * An activity-managed pool of globally-valid cuts (#40). Cuts are added with deduplication by
 * [Cut.key]; a hard [maxCuts] cap bounds the per-node LP cost the pool imposes once its cuts are folded
 * into every node's relaxation. When the cap is exceeded the least-active cuts are evicted, where
 * activity is tightness at the LP optimum — the CP-SAT cut-management signal: a cut the LP point sits
 * on shapes the relaxation face, while a slack cut is dead weight on every solve.
 *
 * Eviction is sound: every pooled cut is valid at every solution ([Cut.global]), so dropping one only
 * loosens the relaxation — it never removes a feasible point. The pool replaces the unbounded
 * accumulation the root harvest used, which could grow without limit (the ghoulomb over-harvest: ~15795
 * cuts for zero prunes). Below the cap the pool preserves insertion order, so it is behaviour-neutral
 * on a harvest that never overflows.
 */
internal class CutPool(val maxCuts: Int = DEFAULT_MAX_CUTS) {
    private val seen = HashSet<String>()
    private val entries = ArrayList<Cut>()

    /** Number of pooled cuts. */
    val size: Int get() = entries.size

    /** Add [cut] unless an equal one (by [Cut.key]) is already pooled; returns true if newly added. */
    fun add(cut: Cut): Boolean {
        if (!seen.add(cut.key())) return false
        entries.add(cut)
        return true
    }

    /** Add each of [cuts] (deduplicated); returns how many were newly added. */
    fun addAll(cuts: Iterable<Cut>): Int {
        var added = 0
        for (c in cuts) if (add(c)) added++
        return added
    }

    /** The pooled cuts, in insertion order (after any [retainMostActive] eviction). */
    fun cuts(): List<Cut> = entries

    /**
     * Evict the least-active cuts until at most [maxCuts] remain, ranking by tightness at the LP point
     * [primal] (per structural column): a cut's slack `|rhs − Σ coeffs·primal|` measures how far the
     * point sits inside it, so ascending slack is most-active-first. Ties keep insertion order (the
     * sort is stable). A no-op when the pool is within the cap, so it leaves a non-overflowing harvest
     * untouched. Sound — the evicted cuts are globally valid, so dropping them only loosens the bound.
     */
    fun retainMostActive(primal: DoubleArray) {
        if (entries.size <= maxCuts) return
        entries.sortBy { slack(it, primal) }
        while (entries.size > maxCuts) entries.removeAt(entries.size - 1)
        seen.clear()
        for (c in entries) seen.add(c.key())
    }

    /** Distance of the LP [primal] point from cut tightness — 0 when the point sits on the cut. */
    private fun slack(cut: Cut, primal: DoubleArray): Double {
        var lhs = 0.0
        for (k in cut.cols.indices) {
            val col = cut.cols[k]
            if (col in primal.indices) lhs += cut.coeffs[k] * primal[col]
        }
        return abs(cut.rhs - lhs)
    }

    internal companion object {
        /**
         * Default cap on the pooled cuts. Bounds the per-node LP solve a large root harvest would
         * otherwise impose, while sitting well above a normal harvest's output so it only bites on a
         * pathological over-harvest. The reported cut count ([size]) reflects any eviction.
         */
        const val DEFAULT_MAX_CUTS: Int = 2048
    }
}
