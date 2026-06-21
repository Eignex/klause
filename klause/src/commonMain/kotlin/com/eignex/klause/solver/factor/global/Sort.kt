package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.remapVars

/**
 * `sort(xs, ys)` — [ys] is the non-decreasing sorted permutation of [xs] (same multiset
 * of values). Two constraints together: pairwise `ys(i) ≤ ys(i+1)` AND the multisets of
 * `xs` and `ys` are equal.
 *
 * Propagation: chain bound-tightening on `ys` (non-decreasing) and matching bounds
 * between `ys(0)` ↔ `min(xs)` / `ys(n-1)` ↔ `max(xs)`.
 */
class Sort(val xs: IntArray, val ys: IntArray) : Factor {

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

    override fun asPropagator(): Propagator = SortPropagator(boolVars, intVars, xs, ys)

    override fun asInvariant(): Invariant = SortInvariant(boolVars, intVars, xs, ys)
}
