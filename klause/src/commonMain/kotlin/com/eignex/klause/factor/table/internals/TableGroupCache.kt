package com.eignex.klause.factor.table.internals

/**
 * Verdict shared across the rows of a `<group>` of table constraints that instantiate one `<extension>`
 * relation (so they share the same `tuples`/`hi` arrays by reference). The first row whose columns all
 * still hold their full contiguous declared domains sweeps the relation once; if that sweep prunes no
 * domain value — the common case at the root, where a dense relation supports every value — the column
 * bounds are cached here, and every later row with the same full bounds skips its own full-table sweep.
 *
 * Whether a domain value is pruned is a pure function of (relation, column bounds), so the verdict
 * transfers to any row over the same relation with the same contiguous bounds — it prunes nothing either,
 * capturing the redundancy of thousands of rows re-discovering that a shared dense relation is already
 * arc-consistent. A skipping row leaves its own tuple set unfiltered, which only defers the STR2 cleanup
 * a real later fire would redo, and never changes a domain. A row that does prune recomputes normally.
 *
 * Written at most once with an immutable value; a benign race under parallel search recomputes the same
 * verdict. Mirrors [MddTransitionIndex.rootSnapshot].
 */
internal class TableGroupCache {
    /** Per-column `[min, max]` bounds under which a full sweep of the shared relation prunes nothing.
     *  Null until the first eligible row establishes it. */
    var noopMins: LongArray? = null
    var noopMaxs: LongArray? = null

    /** Whether [mins]/[maxs] match the cached no-op bounds exactly (same length, same per-column bounds). */
    fun isNoop(mins: LongArray, maxs: LongArray): Boolean {
        val cm = noopMins ?: return false
        val cx = noopMaxs ?: return false
        if (cm.size != mins.size) return false
        for (i in mins.indices) if (cm[i] != mins[i] || cx[i] != maxs[i]) return false
        return true
    }

    /** Record that the shared relation is arc-consistent under column bounds [mins]/[maxs] (copies taken). */
    fun setNoop(mins: LongArray, maxs: LongArray) {
        if (noopMins != null) return
        noopMaxs = maxs.copyOf()
        noopMins = mins.copyOf()
    }
}
