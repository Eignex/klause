package com.eignex.klause.factor.table.internals

/**
 * Immutable root reachability for a layered diagram, valid for any factor whose positions all still admit
 * the whole alphabet (so the per-position domain filter is a no-op and reachability is purely structural).
 * [fwd]/[bwd] are the forward/backward state-reachability bitsets (`(n+1) * w` longs, the same layout the
 * per-factor sweeps use); [survivors] is, per position, the bitset of alphabet symbols — offset by the
 * diagram's `minSym` — that keep the position alive. A reusing factor copies [fwd]/[bwd] and derives its
 * own exclusions by testing its live domain against [survivors], instead of re-scanning the diagram.
 */
internal class MddRootSnapshot(val fwd: LongArray, val bwd: LongArray, val survivors: Array<LongArray>)

/**
 * Per-layer CSR indices over a layered MDD's transition records, keyed by source state ([fwdHead] into
 * [fwdPtr]) and by destination state ([bwdHead] into [bwdSrc]/[bwdSym]). In layer `i`, the records leaving
 * source state `s` are `fwdPtr(i)` over the half-open range `fwdHead(i)(s) .. fwdHead(i)(s+1)`, each entry
 * a base offset into the transition array. The incoming records for destination state `d` are `bwdHead(i)`
 * over `bwdHead(i)(d) .. bwdHead(i)(d+1)`; their source and symbol are stored *inline* in [bwdSrc]/[bwdSym]
 * rather than as offsets into the transition array. Transitions are laid out source-major, so a backward
 * sweep that dereferenced offsets would touch the transition array at scattered addresses (one cache miss
 * per incoming edge); the inline copy keeps the backward sweep's reads sequential. Records whose source
 * (resp. destination) is outside its layer's state range are dropped — such a record never matches a set
 * reachability bit, so the sweeps behave identically.
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
    val bwdSrc: Array<IntArray>,
    val bwdSym: Array<LongArray>,
    /** Smallest and largest symbol over every transition (the full alphabet the diagram uses). */
    val minSym: Long,
    val maxSym: Long,
) {
    /**
     * Root reachability shared across a `<group>`'s factors, computed once by the first factor whose
     * positions all still admit the whole alphabet and reused by the rest instead of re-scanning the
     * diagram (the structural forward/backward bitsets are domain-independent there). Written at most once
     * with an immutable value; a benign race under parallel search recomputes the same snapshot.
     */
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
