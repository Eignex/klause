package com.eignex.klause.factor.table

import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray

/**
 * Layered multi-valued decision diagram acceptance. The diagram has `n+1` state layers
 * (`n = seq.size`); layer `i` has `numStatesPerLayer(i)` states. [transitions] is a flat
 * sequence of `(srcState, value, dstState[, weight])` rows; [layerStarts] indexes into it
 * (layer i spans `layerStarts(i) until layerStarts(i+1)`).
 *
 * When `weights` is non-null, each transition has a 4th field (weight) and [cost] must be
 * provided — the sum of edge weights along the accepted path equals [cost].
 *
 * Propagation:
 *  - Forward sweep: state `s` at layer `i` is forward-reachable iff some forward-reachable
 *    state at layer `i-1` has a transition on a feasible (in-domain) symbol leading to `s`.
 *  - Backward sweep symmetric from accepting states.
 *  - Prune `seq(i)` values that have no transition between forward∩backward reachable states.
 *  - Fail if no forward-reachable state at layer `n` is accepting.
 *  - For cost variant: tighten [cost] bounds by min/max weighted-sum path through the
 *    forward-backward reachable lattice.
 */
class Mdd(
    /** Sequence variable ids, one per layer. */
    val seq: IntArray,
    /** Number of states in each layer (length `seq.size + 1`). */
    val numStatesPerLayer: IntArray,
    /** Prefix-sum index into [transitions] per layer. */
    val layerStarts: IntArray,
    /** Flat transition records; stride [recordStride]. */
    val transitions: IntArray,
    /** Start state. */
    val initial: Int,
    /** Accepting states at the final layer. */
    val accepting: IntArray,
    /** Ints per transition record: 3 for plain MDD, 4 for cost MDD. */
    val recordStride: Int,
    /** Cost variable id, or -1 for a plain (non-cost) MDD. */
    val cost: Int = -1,
) : Factor {

    init {
        require(seq.isNotEmpty()) { "Mdd: empty seq" }
        require(numStatesPerLayer.size == seq.size + 1) { "Mdd: numStatesPerLayer must be seq.size+1" }
        require(layerStarts.size == seq.size + 1) { "Mdd: layerStarts must be seq.size+1" }
        require(recordStride == 3 || recordStride == 4) { "Mdd: stride must be 3 or 4" }
        require(transitions.size % recordStride == 0) { "Mdd: transitions length not a multiple of stride" }
        if (recordStride == 4) require(cost >= 0) { "Mdd: cost-MDD requires cost var" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Mdd(
        seq.remapVars(intMap),
        numStatesPerLayer,
        layerStarts,
        transitions,
        initial,
        accepting,
        recordStride,
        if (cost >= 0) intMap[cost] else cost,
    )

    /** Position-faithful (layer i matters): keeps the sequence vars in order and folds in the whole
     *  diagram — per-layer state counts, layer offsets, the transition records, the initial and
     *  accepting states, the record stride, and the cost var (#531). */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.MDD) {
        int(initial)
        int(recordStride)
        int(cost)
        ints(numStatesPerLayer)
        ints(layerStarts)
        ints(transitions)
        ints(accepting)
        ints(seq)
    }

    /** Symbol relabeling (#536): each transition record is `(fromState, symbol, toState[, cost])`, so a
     *  value permutation maps the symbol field of every record. Sound — the `seq` values are the
     *  symbols and there is no positional-variable/constant coupling (unlike Element). No bijection
     *  check is needed: records carry the symbol explicitly, so any map yields a valid diagram and the
     *  verification's key comparison decides whether the relabeling is actually a symmetry. */
    override fun remapValues(valueMap: (Int) -> Int): Factor {
        val newTransitions = transitions.copyOf()
        var p = 0
        while (p < newTransitions.size) {
            newTransitions[p + 1] = valueMap(newTransitions[p + 1])
            p += recordStride
        }
        return Mdd(seq, numStatesPerLayer, layerStarts, newTransitions, initial, accepting, recordStride, cost)
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = if (cost >= 0) seq + intArrayOf(cost) else seq.copyOf()

    override fun asPropagator(): Propagator = MddPropagator(
        boolVars, intVars, seq, numStatesPerLayer, layerStarts, transitions, initial, accepting, recordStride, cost,
    )

    override fun asInvariant(): Invariant = MddInvariant(
        seq,
        numStatesPerLayer,
        layerStarts,
        transitions,
        initial,
        accepting,
        recordStride,
        cost,
    )

    override fun asLinearizer(): Linearizer = MddLinearizer(
        seq,
        numStatesPerLayer,
        layerStarts,
        transitions,
        initial,
        accepting,
        recordStride,
        cost,
    )
}
