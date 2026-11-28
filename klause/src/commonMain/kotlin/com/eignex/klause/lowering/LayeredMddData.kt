package com.eignex.klause.lowering

import com.eignex.klause.factor.table.Mdd
import com.eignex.util.collections.LongArrayList

// The sequence-independent structure of a plain [Mdd], shared by a group of identical diagrams. Transitions
// are `(srcLocal, symbol, dstLocal)` records grouped by layer.
internal class LayeredMddData(
    val numStatesPerLayer: IntArray,
    val layerStarts: IntArray,
    val transitions: LongArray,
    val initial: Int,
    val accepting: IntArray,
) {
    val nLayers: Int get() = numStatesPerLayer.size - 1

    fun toMdd(seq: IntArray): Mdd = Mdd(
        seq = seq,
        numStatesPerLayer = numStatesPerLayer,
        layerStarts = layerStarts,
        transitions = transitions,
        initial = initial,
        accepting = accepting,
        recordStride = 3,
    )
}

// Shared by the XCSP3 and FlatZinc `mdd` front-ends, which derive node layers and local indices their own
// way. Records keep the edge order, so a caller that preserves its edge order gets a stable diagram.
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
