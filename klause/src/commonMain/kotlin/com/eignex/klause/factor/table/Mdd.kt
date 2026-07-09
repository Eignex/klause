package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.remapVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.HullFamily
import com.eignex.klause.lp.LpSizeEstimate
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList

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
    /** Flat transition records `(src, symbol, dst[, weight])`; stride [recordStride]. The symbol column
     *  holds domain values (possibly wide); `src`/`dst`/`weight` are small ids read as [Int]. */
    val transitions: LongArray,
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
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.MDD, ::buildKey)

    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.MDD, boolMap, intMap, ::buildKey)

    // The diagram (states, layer offsets, transition records, accepting set) is constant structure;
    // seq are the sequence's int-var ids; cost is an int var or a negative sentinel.
    private fun buildKey(sink: KeySink) {
        sink.int(initial)
        sink.int(recordStride)
        sink.intVarOrSelf(cost)
        sink.constInts(numStatesPerLayer)
        sink.constInts(layerStarts)
        sink.constLongs(transitions)
        sink.constInts(accepting)
        sink.intVars(seq)
    }

    /** Symbol relabeling (#536): each transition record is `(fromState, symbol, toState[, cost])`, so a
     *  value permutation maps the symbol field of every record. Sound — the `seq` values are the
     *  symbols and there is no positional-variable/constant coupling (unlike Element). No bijection
     *  check is needed: records carry the symbol explicitly, so any map yields a valid diagram and the
     *  verification's key comparison decides whether the relabeling is actually a symmetry. */
    override fun remapValues(valueMap: (Long) -> Long): Factor {
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

    // The LP relaxation's arc presence column is Int-typed; skip it when a symbol exceeds Int range
    // (the propagator/invariant still enforce the diagram). Sound — a relaxation may omit a factor.
    override val hullFamily: HullFamily = HullFamily.MDD

    /**
     * Layered flow hull — the exact convex hull of the diagram's accepting paths. An arc variable
     * `y ∈ [0,1]` per forward-reachable transition record `(src, value, dst[, weight])` at each layer
     * (pinned to 0 when `value` left the live domain of `seq[layer]`). Rows: a source row, flow conservation
     * at every interior `(layer, state)`, an acceptance row, a value channel `Σ value·y = seq[layer]` per
     * layer, and — for a cost-MDD (stride 4) — a cost channel `Σ weight·y = cost`. The flow polytope is
     * integral, so the LP optimum is exact. Symbols and weights are carried as [Long], so a wide
     * transition table relaxes soundly; `src`/`dst` are per-layer state ids, always in [Int] range.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!builder.hullEnabled()) return
        val reach = forwardReach(builder::declaredDomain)?.states ?: return
        val n = seq.size
        val stride = recordStride
        val trans = transitions
        val starts = layerStarts

        // States are layer-local dense ids in `[0, numStatesPerLayer(layer))`, so the per-layer
        // state→arc-columns maps are arrays indexed straight by the state id (#678).
        val nspl = numStatesPerLayer
        val outCols = Array(n) { arrayOfNulls<IntArrayList>(nspl[it]) }
        val inCols = Array(n + 1) { arrayOfNulls<IntArrayList>(nspl[it]) }
        val chanCols = Array(n) { IntArrayList() }
        val chanVal = Array(n) { LongArrayList() }
        val acceptingSet = IntHashSet(accepting.size).apply { for (a in accepting) add(a) }
        val acceptCols = IntArrayList()
        val costArcs = IntArrayList()
        val costWeight = LongArrayList()
        for (layer in 0 until n) {
            val declared = builder.declaredDomain(seq[layer])
            val live = builder.liveDomain(seq[layer])
            var p = starts[layer]
            val end = starts[layer + 1]
            while (p < end) {
                val src = trans[p].toInt()
                val value = trans[p + 1]
                val dst = trans[p + 2].toInt()
                if (src in reach[layer] && value in declared) {
                    // The arc is present while its value stays in seq[layer]'s live domain.
                    val col = builder.auxColumn(
                        0L,
                        if (live.contains(value)) 1L else 0L,
                        presence = longArrayOf(seq[layer].toLong(), value),
                    )
                    (outCols[layer][src] ?: IntArrayList().also { outCols[layer][src] = it }).add(col)
                    (inCols[layer + 1][dst] ?: IntArrayList().also { inCols[layer + 1][dst] = it }).add(col)
                    chanCols[layer].add(col)
                    chanVal[layer].add(value)
                    if (layer == n - 1 && dst in acceptingSet) acceptCols.add(col)
                    if (stride == 4) {
                        costArcs.add(col)
                        costWeight.add(trans[p + 3])
                    }
                }
                p += stride
            }
        }
        val src = outCols[0][initial] ?: return
        if (src.isEmpty()) return
        builder.row(src.toIntArray(), LongArray(src.size) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
        for (layer in 1 until n) {
            reach[layer].forEach { state ->
                val cols = IntArrayList()
                val vals = LongArrayList()
                outCols[layer][state]?.let {
                    for (k in 0 until it.size) {
                        cols.add(it[k])
                        vals.add(1L)
                    }
                }
                inCols[layer][state]?.let {
                    for (k in 0 until it.size) {
                        cols.add(it[k])
                        vals.add(-1L)
                    }
                }
                if (!cols.isEmpty()) {
                    builder.row(cols.toIntArray(), vals.toLongArray(), LinearOp.EQ, 0L, Contribution.HULL)
                }
            }
        }
        if (acceptCols.isEmpty()) return
        builder.row(acceptCols.toIntArray(), LongArray(acceptCols.size) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
        for (layer in 0 until n) {
            val k = chanCols[layer].size
            if (k == 0) return
            val cols = IntArray(k + 1)
            val vals = LongArray(k + 1)
            for (i in 0 until k) {
                cols[i] = chanCols[layer][i]
                vals[i] = chanVal[layer][i]
            }
            cols[k] = builder.intColumn(seq[layer])
            vals[k] = -1L
            builder.row(cols, vals, LinearOp.EQ, 0L, Contribution.HULL)
        }
        // Cost channel: Σ weight·y − cost = 0, an exact lower bound on the cost var.
        if (cost >= 0 && !costArcs.isEmpty()) {
            val k = costArcs.size
            val cols = IntArray(k + 1)
            val vals = LongArray(k + 1)
            for (i in 0 until k) {
                cols[i] = costArcs[i]
                vals[i] = costWeight[i]
            }
            cols[k] = builder.intColumn(cost)
            vals[k] = -1L
            builder.row(cols, vals, LinearOp.EQ, 0L, Contribution.HULL)
        }
    }

    override fun lpSizeEstimate(domains: Array<IntDomain>): LpSizeEstimate? {
        val reach = forwardReach { domains[it] } ?: return null
        // arc columns + conservation (≤ arcs) + value channel (n) + source + acceptance + cost.
        return LpSizeEstimate(cols = reach.arcCount, rows = reach.arcCount + seq.size + 3L)
    }

    private class Reach(val states: Array<IntHashSet>, val arcCount: Long)

    /** Forward-reachable states per layer over [domainOf]'s domains plus the total candidate-arc count,
     *  or null when a layer empties (no accepting path) or the arc count is 0 or over [MAX_MDD_ARCS].
     *  Shared by [linearize] (which needs the states to lay out columns) and [lpSizeEstimate] (the count). */
    private fun forwardReach(domainOf: (Int) -> IntDomain): Reach? {
        val n = seq.size
        val stride = recordStride
        val trans = transitions
        val starts = layerStarts
        val reach = Array(n + 1) { IntHashSet() }
        reach[0].add(initial)
        var arcCount = 0L
        for (layer in 0 until n) {
            val dom = domainOf(seq[layer])
            var p = starts[layer]
            val end = starts[layer + 1]
            while (p < end) {
                if (trans[p].toInt() in reach[layer] && trans[p + 1] in dom) {
                    reach[layer + 1].add(trans[p + 2].toInt())
                    arcCount++
                }
                p += stride
            }
            if (reach[layer + 1].isEmpty()) return null // no accepting path under these domains
        }
        if (arcCount == 0L || arcCount > MAX_MDD_ARCS) return null
        return Reach(reach, arcCount)
    }

    private companion object {
        /** Above this many reachable arcs the hull is skipped — the arc columns would dominate. */
        const val MAX_MDD_ARCS: Int = 4096
    }
}
