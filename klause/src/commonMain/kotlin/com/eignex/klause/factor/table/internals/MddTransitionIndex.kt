package com.eignex.klause.factor.table.internals

import kotlin.concurrent.Volatile

// Root reachability, valid for any factor whose positions all admit the whole alphabet, where reachability is
// purely structural. [survivors] holds per position the symbols, offset by `minSym`, that keep it alive; a reusing
// factor tests its live domain against them instead of re-scanning the diagram.
internal class MddRootSnapshot(val fwd: LongArray, val bwd: LongArray, val survivors: Array<LongArray>)

// Per-layer CSR indices over the transition records, by source state ([fwdHead] into [fwdPtr], offsets into the
// transition array) and by destination ([bwdHead] into [bwdSrc]/[bwdSym]). The backward side stores source and
// symbol inline: transitions are source-major, so dereferencing offsets would cost a cache miss per incoming edge.
// Records outside their layer's state range are dropped, since they never match a reachability bit. The index
// depends only on the diagram's structure, so factors over the same diagram share it.
internal class MddTransitionIndex(
    val fwdHead: Array<IntArray>,
    val fwdPtr: Array<IntArray>,
    val bwdHead: Array<IntArray>,
    val bwdSrc: Array<IntArray>,
    val bwdSym: Array<LongArray>,
    val minSym: Long,
    val maxSym: Long,
) {
    private class PreparedSymbols(val columns: Array<ExtensionalSupportIndex.Column>?)

    @Volatile
    private var preparedSymbols: PreparedSymbols? = null

    fun symbolSupports(transitions: LongArray, poll: () -> Unit): Array<ExtensionalSupportIndex.Column>? {
        preparedSymbols?.let { return it.columns }
        val columns = ArrayList<ExtensionalSupportIndex.Column>(fwdPtr.size)
        var remaining = ExtensionalSupportIndex.MAX_BYTES
        for (layer in fwdPtr.indices) {
            val pointers = fwdPtr[layer]
            val column = ExtensionalSupportIndex.buildColumn(pointers.size, remaining, poll) { row ->
                transitions[pointers[row] + 1]
            }
            if (column == null) {
                preparedSymbols = PreparedSymbols(null)
                return null
            }
            remaining -= column.retainedBytes
            columns.add(column)
        }
        poll()
        return columns.toTypedArray().also { preparedSymbols = PreparedSymbols(it) }
    }

    // Set by the first factor whose positions admit the whole alphabet. Written at most once with an immutable
    // value; a race under parallel search recomputes the same snapshot.
    var rootSnapshot: MddRootSnapshot? = null
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
            val bwdSrc = Array(n) { IntArray(0) }
            val bwdSym = Array(n) { LongArray(0) }
            var minSym = Long.MAX_VALUE
            var maxSym = Long.MIN_VALUE
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
                val bSrc = IntArray(bHead[numN])
                val bSym = LongArray(bHead[numN])
                val fCur = fHead.copyOf()
                val bCur = bHead.copyOf()
                p = start
                while (p < end) {
                    val src = transitions[p].toInt()
                    val sym = transitions[p + 1]
                    val dst = transitions[p + 2].toInt()
                    if (sym < minSym) minSym = sym
                    if (sym > maxSym) maxSym = sym
                    if (src in 0 until numI) fPtr[fCur[src]++] = p
                    if (dst in 0 until numN) {
                        val slot = bCur[dst]++
                        bSrc[slot] = src
                        bSym[slot] = sym
                    }
                    p += recordStride
                }
                fwdHead[i] = fHead
                fwdPtr[i] = fPtr
                bwdHead[i] = bHead
                bwdSrc[i] = bSrc
                bwdSym[i] = bSym
            }
            if (minSym > maxSym) {
                minSym = 0L
                maxSym = -1L
            }
            return MddTransitionIndex(fwdHead, fwdPtr, bwdHead, bwdSrc, bwdSym, minSym, maxSym)
        }
    }
}
