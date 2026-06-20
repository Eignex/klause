package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.propagation.IntEvent

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
    override val xs: IntArray,
    /** Integer representing index 0 of [xs]. */
    override val indexOffset: Int = 0,
) : Factor,
    SymmetricAllDifferentPropagator,
    SymmetricAllDifferentInvariant {

    init {
        require(xs.isNotEmpty()) { "symmetric_all_different: empty xs" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        SymmetricAllDifferent(xs.remapVars(intMap), indexOffset)

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    /**
     * Advisor subscription (#623): `propagate` reads only each variable's `min`/`max` — it tightens
     * into the index range, detects clashes among already-fixed variables, and forces the involution
     * mirror of a fixed variable. An interior hole moves no bound and fixes nothing, so the factor
     * subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED] per variable and skips interior
     * `VALUE_REMOVED` wakes (fixing collapses both bounds, so it is covered).
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
