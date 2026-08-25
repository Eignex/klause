package com.eignex.klause.factor.global

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.SpanIntVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.VarRemap
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey

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

    override fun remap(mapping: VarRemap): Factor = Sort(mapping.ints(xs), mapping.ints(ys))

    /** `ys` is the sorted permutation of `xs`: the input multiset ignores order (so `xs` is sorted in
     *  the key), while `ys` is position-faithful (`ys(0) <= ys(1) <= ...`) and kept in order. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.SORT, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.SORT, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.sortedIntVars(xs)
        sink.intVars(ys)
    }

    override val variables: VarList = SpanIntVars(xs + ys)

    override fun asPropagator(): Propagator = SortPropagator(boolVars, intVars, xs, ys)

    override fun asInvariant(): Invariant = SortInvariant(xs, ys)
}
