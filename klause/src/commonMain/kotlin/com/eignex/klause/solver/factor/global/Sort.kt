package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.propagation.IntEvent

/**
 * `sort(xs, ys)` — [ys] is the non-decreasing sorted permutation of [xs] (same multiset
 * of values). Two constraints together: pairwise `ys(i) ≤ ys(i+1)` AND the multisets of
 * `xs` and `ys` are equal.
 *
 * Propagation: chain bound-tightening on `ys` (non-decreasing) and matching bounds
 * between `ys(0)` ↔ `min(xs)` / `ys(n-1)` ↔ `max(xs)`.
 */
class Sort(override val xs: IntArray, override val ys: IntArray) :
    Factor,
    SortPropagator,
    SortInvariant {

    init {
        require(xs.size == ys.size) { "sort: xs/ys size mismatch" }
        require(xs.isNotEmpty()) { "sort: empty arrays" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Sort(xs.remapVars(intMap), ys.remapVars(intMap))

    /** `ys` is the sorted permutation of `xs`: the input multiset ignores order (so `xs` is sorted in
     *  the key), while `ys` is position-faithful (`ys(0) <= ys(1) <= ...`) and kept in order (#443). */
    override fun structuralKey(): String = "sort:" + xs.sorted().joinToString(",") + ":" + ys.joinToString(",")

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + ys

    /**
     * Advisor subscription (#623): the sort propagator reads only each variable's `min`/`max` — the
     * non-decreasing chain on `ys`, the first/last `ys` bounds from the `xs` extremes, the `xs`
     * clamp to the `ys` range, and the all-fixed sanity check. None of it inspects interior holes,
     * so the factor subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] per variable and skips
     * interior `VALUE_REMOVED` wakes.
     */
    override val initialIntEventWatches: IntArray = run {
        val distinct = intVars.toHashSet()
        val out = IntArray(distinct.size * 2)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
        out
    }
}
