package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.lp.LinearizerEstimate
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList

/**
 * Layered flow hull of one [Mdd] — the exact convex hull of the diagram's accepting paths. An arc
 * variable `y ∈ [0,1]` per forward-reachable transition record `(src, value, dst[, weight])` at each
 * layer (pinned to 0 when `value` left the live domain of `seq[layer]`). Rows: a source row, flow
 * conservation at every interior `(layer, state)`, an acceptance row at the final layer, a value channel
 * `Σ value·y = seq[layer]` per layer, and — for a cost-MDD (stride 4) — a cost channel `Σ weight·y =
 * cost`, an exact lower bound on the cost variable. The flow polytope is integral, so the LP optimum is
 * exact. Forward reachability over the declared domains bounds the arc count ([MAX_MDD_ARCS]). HULL.
 */
internal class MddLinearizer(
    private val seq: IntArray,
    private val numStatesPerLayer: IntArray,
    private val layerStarts: IntArray,
    private val transitions: LongArray,
    private val initial: Int,
    private val accepting: IntArray,
    private val recordStride: Int,
    private val cost: Int,
) : Linearizer {
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
        val costWeight = IntArrayList()
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
                        presence = intArrayOf(seq[layer], value.toInt()),
                    )
                    (outCols[layer][src] ?: IntArrayList().also { outCols[layer][src] = it }).add(col)
                    (inCols[layer + 1][dst] ?: IntArrayList().also { inCols[layer + 1][dst] = it }).add(col)
                    chanCols[layer].add(col)
                    chanVal[layer].add(value)
                    if (layer == n - 1 && dst in acceptingSet) acceptCols.add(col)
                    if (stride == 4) {
                        costArcs.add(col)
                        costWeight.add(trans[p + 3].toInt())
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
                vals[i] = costWeight[i].toLong()
            }
            cols[k] = builder.intColumn(cost)
            vals[k] = -1L
            builder.row(cols, vals, LinearOp.EQ, 0L, Contribution.HULL)
        }
    }

    override fun sizeEstimate(domains: Array<IntDomain>): LinearizerEstimate? {
        val reach = forwardReach { domains[it] } ?: return null
        // arc columns + conservation (≤ arcs) + value channel (n) + source + acceptance + cost.
        return LinearizerEstimate(cols = reach.arcCount, rows = reach.arcCount + seq.size + 3L)
    }

    private class Reach(val states: Array<IntHashSet>, val arcCount: Long)

    /** Forward-reachable states per layer over [domainOf]'s domains plus the total candidate-arc count,
     *  or null when a layer empties (no accepting path) or the arc count is 0 or over [MAX_MDD_ARCS].
     *  Shared by [linearize] (which needs the states to lay out columns) and [sizeEstimate] (the count). */
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

    companion object {
        /** Above this many reachable arcs the hull is skipped — the arc columns would dominate. */
        const val MAX_MDD_ARCS: Int = 4096
    }
}
