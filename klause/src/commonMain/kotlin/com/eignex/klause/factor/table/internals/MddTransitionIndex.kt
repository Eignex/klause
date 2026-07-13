package com.eignex.klause.factor.table.internals

/**
 * Per-layer CSR indices over a layered MDD's transition records, keyed by source state ([fwdHead] into
 * [fwdPtr]) and by destination state ([bwdHead] into [bwdPtr]). In layer `i`, the records leaving source
 * state `s` are `fwdPtr(i)` over the half-open range `fwdHead(i)(s) .. fwdHead(i)(s+1)`, each entry a base
 * offset into the transition array; `bwdPtr`/`bwdHead` group the incoming records by destination state.
 * Records whose source (resp. destination) is outside its layer's state range are dropped — such a record
 * never matches a set reachability bit, so the sweeps behave identically.
 *
 * The index depends only on the diagram's structure (`transitions`, `layerStarts`, `numStatesPerLayer`),
 * not on the sequence variables, the symbols, or the cost weights. A `<group>` of identical diagrams —
 * hundreds of factors over one shared structure — therefore builds it once and shares the instance, and
 * it survives both variable and value remapping unchanged.
 */
internal class MddTransitionIndex(
    val fwdHead: Array<IntArray>,
    val fwdPtr: Array<IntArray>,
    val bwdHead: Array<IntArray>,
    val bwdPtr: Array<IntArray>,
) {
    companion object {
        fun build(
            transitions: LongArray,
            layerStarts: IntArray,
            numStatesPerLayer: IntArray,
            recordStride: Int,
        ): MddTransitionIndex {
            val n = numStatesPerLayer.size - 1
            val fwdHead = Array(n) { IntArray(0) }
            val fwdPtr = Array(n) { IntArray(0) }
            val bwdHead = Array(n) { IntArray(0) }
            val bwdPtr = Array(n) { IntArray(0) }
            for (i in 0 until n) {
                val numI = numStatesPerLayer[i]
                val numN = numStatesPerLayer[i + 1]
                val start = layerStarts[i]
                val end = layerStarts[i + 1]
                val fHead = IntArray(numI + 1)
                val bHead = IntArray(numN + 1)
                var p = start
                while (p < end) {
                    val src = transitions[p].toInt()
                    val dst = transitions[p + 2].toInt()
                    if (src in 0 until numI) fHead[src + 1]++
                    if (dst in 0 until numN) bHead[dst + 1]++
                    p += recordStride
                }
                for (k in 0 until numI) fHead[k + 1] += fHead[k]
                for (k in 0 until numN) bHead[k + 1] += bHead[k]
                val fPtr = IntArray(fHead[numI])
                val bPtr = IntArray(bHead[numN])
                val fCur = fHead.copyOf()
                val bCur = bHead.copyOf()
                p = start
                while (p < end) {
                    val src = transitions[p].toInt()
                    val dst = transitions[p + 2].toInt()
                    if (src in 0 until numI) fPtr[fCur[src]++] = p
                    if (dst in 0 until numN) bPtr[bCur[dst]++] = p
                    p += recordStride
                }
                fwdHead[i] = fHead
                fwdPtr[i] = fPtr
                bwdHead[i] = bHead
                bwdPtr[i] = bPtr
            }
            return MddTransitionIndex(fwdHead, fwdPtr, bwdHead, bwdPtr)
        }
    }
}
