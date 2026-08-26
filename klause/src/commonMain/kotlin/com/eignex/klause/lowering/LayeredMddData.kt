package com.eignex.klause.lowering

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.internals.MddTransitionIndex
import com.eignex.klause.util.LongArrayList

/** The sequence-independent layered structure of a plain (non-cost) [Mdd]: per-layer state counts, the
 *  transition records `(srcLocal, symbol, dstLocal)` grouped by layer, the [layerStarts] offsets into
 *  them, and the local indices of the initial and accepting states. A `<group>` of identical diagrams
 *  shares one of these; only [toMdd]'s sequence varies per instance. */
internal class LayeredMddData(
    val numStatesPerLayer: IntArray,
    val layerStarts: IntArray,
    val transitions: LongArray,
    val initial: Int,
    val accepting: IntArray,
) {
    /** Number of sequence positions the diagram accepts (`numStatesPerLayer.size - 1`). */
    val nLayers: Int get() = numStatesPerLayer.size - 1

    // The transition index depends only on the shared structure, so a `<group>`'s diagrams build it once
    // between them rather than one copy per factor — decisive when a group holds hundreds of wide MDDs.
    private val sharedIndex by lazy(LazyThreadSafetyMode.NONE) {
        MddTransitionIndex.build(transitions, layerStarts, numStatesPerLayer, recordStride = 3)
    }

    fun toMdd(seq: IntArray): Mdd = Mdd(
        seq = seq,
        numStatesPerLayer = numStatesPerLayer,
        layerStarts = layerStarts,
        transitions = transitions,
        initial = initial,
        accepting = accepting,
        recordStride = 3,
    ).also { it.transitionIndex = sharedIndex }
}

/**
 * Pack a layered MDD's records from a diagram whose nodes already carry a layer and a per-layer local
 * index. Shared by the XCSP3 and FlatZinc `mdd` front-ends: each derives node layers/local indices its
 * own way (XCSP3 computes layers by depth from the root; FlatZinc reads explicit levels and collapses
 * terminal-level nodes), then this groups the edges into per-layer `(srcLocal, symbol, dstLocal)` rows.
 *
 * Node ids are dense in `[0, nodeLayer.size)`; edge `k` runs from a layer-`L` node to a layer-`L+1` one.
 * Records keep the edge order, so a caller that preserves its edge order gets a stable diagram.
 */
internal fun packLayeredMdd(
    nLayers: Int,
    numStatesPerLayer: IntArray,
    localIdx: IntArray,
    nodeLayer: IntArray,
    edgeSrc: IntArray,
    edgeSym: LongArray,
    edgeDst: IntArray,
    initialNode: Int,
    acceptingNodes: IntArray,
): LayeredMddData {
    val perLayer = Array(nLayers + 1) { LongArrayList() }
    for (k in edgeSrc.indices) {
        val layer = perLayer[nodeLayer[edgeSrc[k]]]
        layer.add(localIdx[edgeSrc[k]].toLong())
        layer.add(edgeSym[k])
        layer.add(localIdx[edgeDst[k]].toLong())
    }
    val transitions = LongArrayList()
    val layerStarts = IntArray(nLayers + 1)
    for (lyr in 0 until nLayers) {
        layerStarts[lyr] = transitions.size
        perLayer[lyr].forEach { transitions.add(it) }
    }
    layerStarts[nLayers] = transitions.size
    return LayeredMddData(
        numStatesPerLayer = numStatesPerLayer,
        layerStarts = layerStarts,
        transitions = transitions.toLongArray(),
        initial = localIdx[initialNode],
        accepting = IntArray(acceptingNodes.size) { localIdx[acceptingNodes[it]] },
    )
}
