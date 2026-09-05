package com.eignex.klause.lp

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList

/**
 * Layered flow hull — the exact convex hull of the diagram's accepting paths. An arc variable
 * `y ∈ [0,1]` represents each forward-reachable transition. Source, conservation, acceptance,
 * value-channel, and optional cost-channel rows describe the path polytope. Large arc sets are skipped.
 *
 * The layer expansion screens each layer's values through its root box and the flow rows then confine the
 * column to the arcs built, so over a side the model leaves open an invented endpoint would refute paths
 * the diagram accepts — declined there.
 */
@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
internal fun Mdd.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    if (!builder.statesBothBounds(seq)) return
    val reach = forwardReach(builder::rootDomain)?.states ?: return
    val n = seq.size
    val stride = recordStride
    val trans = transitions
    val starts = layerStarts
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
        val box = builder.rootDomain(seq[layer])
        val live = builder.liveDomain(seq[layer])
        var p = starts[layer]
        val end = starts[layer + 1]
        while (p < end) {
            val src = trans[p].toInt()
            val value = trans[p + 1]
            val dst = trans[p + 2].toInt()
            if (src in reach[layer] && value in box) {
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

internal fun Mdd.estimateLpHull(boxes: RootBoxes): LpSizeEstimate? {
    if (!boxes.statesBothBounds(seq)) return null
    val reach = forwardReach(boxes::domain) ?: return null
    return LpSizeEstimate(cols = reach.arcCount, rows = reach.arcCount + seq.size + 3L)
}

private class MddReach(val states: Array<IntHashSet>, val arcCount: Long)

private fun Mdd.forwardReach(domainOf: (Int) -> IntDomain): MddReach? {
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
        if (reach[layer + 1].isEmpty()) return null
    }
    if (arcCount == 0L || arcCount > MAX_MDD_ARCS) return null
    return MddReach(reach, arcCount)
}

/** Above this many reachable arcs the hull is skipped — the arc columns would dominate. */
private const val MAX_MDD_ARCS: Int = 4096
