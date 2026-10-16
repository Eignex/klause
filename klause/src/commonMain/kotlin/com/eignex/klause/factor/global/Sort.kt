package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.SpanIntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey

/**
 * `sort(xs, ys)` — [ys] is the non-decreasing sorted permutation of [xs]: `ys(i) ≤ ys(i+1)` and the
 * multisets of `xs` and `ys` are equal. Propagation is bound-consistent over both arrays.
 */
class Sort(val xs: IntArray, val ys: IntArray) : Factor {

    init {
        require(xs.size == ys.size) { "sort: xs/ys size mismatch" }
        require(xs.isNotEmpty()) { "sort: empty arrays" }
    }

    override fun remap(mapping: VarRemap): Factor = Sort(mapping.ints(xs), mapping.ints(ys))

    /** The input multiset ignores order, so `xs` is sorted in the key; `ys` is positional and kept in order. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.SORT, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.SORT, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.sortedIntVars(xs)
        sink.intVars(ys)
    }

    override val variables: VarList = SpanIntVars(xs + ys)
}
