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
 * `symmetric_all_different(xs)` — `xs` is a self-inverse permutation: `xs(xs(i)) = i` for
 * every `i`. Strictly stronger than `all_different` (which just demands distinctness):
 * each value also points back to its pointer.
 *
 * [indexOffset] is the value `xs(0)` would take to mean position 0 — typically `1` for
 * the MZN 1-based default.
 *
 * Propagation: all-different singleton-conflict detection inherited from `AllDifferent`,
 * plus a self-inverse check on singletons.
 */
class SymmetricAllDifferent(
    /** Involution variable ids: `xs(i)` and its image must pair symmetrically. */
    val xs: IntArray,
    /** Integer representing index 0 of [xs]. */
    val indexOffset: Int = 0,
) : Factor {

    init {
        require(xs.isNotEmpty()) { "symmetric_all_different: empty xs" }
    }

    override fun remap(mapping: VarRemap): Factor = SymmetricAllDifferent(mapping.ints(xs), indexOffset)

    /** A self-inverse permutation references positions (`xs(xs(i)) = i`), so [xs] is positional;
     *  [indexOffset] names the value of index 0. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.SYMMETRIC_ALL_DIFFERENT, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.SYMMETRIC_ALL_DIFFERENT, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.int(indexOffset)
        sink.intVars(xs)
    }

    override val variables: VarList = SpanIntVars(xs)

    override fun asPropagator(): Propagator = SymmetricAllDifferentPropagator(boolVars, intVars, xs, indexOffset)

    override fun asInvariant(): Invariant = SymmetricAllDifferentInvariant(xs, indexOffset)
}
