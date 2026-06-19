package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.RevInt
import com.eignex.klause.solver.propagation.RevLongArray

/*
 * Reversible, delta-driven layered-MDD GAC — the incremental counterpart to the full forward+backward
 * bitset rebuild Mdd.propagate ran every fire. Per layer a state-bitset records forward-reachability
 * from [initial] and backward-co-reachability to an accepting state, both on the engine undo trail
 * ([RevLongArray], one uniform-width slot per layer). A symbol value at position i survives iff some
 * forward-reachable state has a transition on it to a backward-co-reachable state. Reachability uses
 * the symbol's *bounds* (`sym in min..max`), exactly as the original — Mdd is bounds-sensitive, so it
 * wakes on bound moves only (interior carves cannot change the bitset reachability). A fire recomputes
 * only the layers a changed position propagates through; a backtrack restores the layers in O(changed
 * words). The cost variant re-derives the cost-variable bounds from the (current) forward lattice.
 *
 * Soundness gated by MddIncrementalTest enumerate-vs-brute under full CDCL across deep backtracking.
 */
internal class MddIncrementalState(
    state: PropagationState,
    private val seq: IntArray,
    private val numStatesPerLayer: IntArray,
    private val layerStarts: IntArray,
    private val transitions: IntArray,
    private val initial: Int,
    private val accepting: IntArray,
    private val recordStride: Int,
    private val cost: Int,
) {
    private val n = seq.size
    private val maxStates = numStatesPerLayer.max()
    private val w = (maxStates + 63) ushr 6

    private val fwd = RevLongArray(state, (n + 1) * w)
    private val bwd = RevLongArray(state, (n + 1) * w)
    private val valid = RevInt(state, 0)

    private fun testBit(rev: RevLongArray, layer: Int, s: Int): Boolean =
        (rev[layer * w + (s ushr 6)] and (1L shl (s and 63))) != 0L

    private fun layerEmpty(rev: RevLongArray, layer: Int): Boolean {
        val base = layer * w
        for (k in 0 until w) if (rev[base + k] != 0L) return false
        return true
    }

    private fun writeLayer(rev: RevLongArray, layer: Int, tmp: LongArray): Boolean {
        val base = layer * w
        var changed = false
        for (k in 0 until w) {
            if (rev[base + k] != tmp[k]) {
                rev[base + k] = tmp[k]
                changed = true
            }
        }
        return changed
    }

    /** fwd[i+1] = { dst : (src,sym,dst) ∈ layer i, src ∈ fwd[i], sym ∈ [min,max](seq[i]) }. */
    private fun recomputeForward(state: PropagationState, i: Int, scratch: LongArray): Boolean {
        scratch.fill(0L)
        val d = state.intDomains[seq[i]]
        val numI = numStatesPerLayer[i]
        val numN = numStatesPerLayer[i + 1]
        var p = layerStarts[i]
        val end = layerStarts[i + 1]
        while (p < end) {
            val src = transitions[p]
            val sym = transitions[p + 1]
            val dst = transitions[p + 2]
            if (src in 0 until numI && dst in 0 until numN && sym in d.min..d.max && testBit(fwd, i, src)) {
                scratch[dst ushr 6] = scratch[dst ushr 6] or (1L shl (dst and 63))
            }
            p += recordStride
        }
        return writeLayer(fwd, i + 1, scratch)
    }

    /** bwd[i] = { src ∈ fwd[i] : (src,sym,dst) ∈ layer i, dst ∈ bwd[i+1], sym ∈ [min,max](seq[i]) }. */
    private fun recomputeBackward(state: PropagationState, i: Int, scratch: LongArray): Boolean {
        scratch.fill(0L)
        val d = state.intDomains[seq[i]]
        val numI = numStatesPerLayer[i]
        val numN = numStatesPerLayer[i + 1]
        var p = layerStarts[i]
        val end = layerStarts[i + 1]
        while (p < end) {
            val src = transitions[p]
            val sym = transitions[p + 1]
            val dst = transitions[p + 2]
            if (src in 0 until numI && dst in 0 until numN && sym in d.min..d.max &&
                testBit(fwd, i, src) && testBit(bwd, i + 1, dst)
            ) {
                scratch[src ushr 6] = scratch[src ushr 6] or (1L shl (src and 63))
            }
            p += recordStride
        }
        return writeLayer(bwd, i, scratch)
    }

    /** bwd[n] = accepting ∩ fwd[n]. */
    private fun recomputeAcceptLayer(scratch: LongArray): Boolean {
        scratch.fill(0L)
        val numN = numStatesPerLayer[n]
        for (s in accepting) {
            if (s in 0 until numN && testBit(fwd, n, s)) scratch[s ushr 6] = scratch[s ushr 6] or (1L shl (s and 63))
        }
        return writeLayer(bwd, n, scratch)
    }

    private fun anyAcceptingForward(): Boolean {
        val numN = numStatesPerLayer[n]
        for (s in accepting) if (s in 0 until numN && testBit(fwd, n, s)) return true
        return false
    }

    /** Prune every position in `[lo, hi]`: a symbol value survives iff some forward-reachable state
     *  transitions on it to a backward-co-reachable state. Returns false on a domain wipeout. */
    private fun prune(state: PropagationState, lo: Int, hi: Int, ant: IntArray?): Boolean {
        for (i in lo..hi) {
            val d = state.intDomains[seq[i]]
            val span = d.max - d.min + 1
            if (span <= 0) continue
            val survives = LongArray((span + 63) ushr 6)
            val numI = numStatesPerLayer[i]
            val numN = numStatesPerLayer[i + 1]
            var p = layerStarts[i]
            val end = layerStarts[i + 1]
            while (p < end) {
                val src = transitions[p]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2]
                if (sym in d.min..d.max && src in 0 until numI && dst in 0 until numN &&
                    testBit(fwd, i, src) && testBit(bwd, i + 1, dst)
                ) {
                    val off = sym - d.min
                    survives[off ushr 6] = survives[off ushr 6] or (1L shl (off and 63))
                }
                p += recordStride
            }
            for (s in d.min..d.max) {
                val off = s - d.min
                if (((survives[off ushr 6] ushr (off and 63)) and 1L) == 0L) {
                    if (!state.excludeIntValue(seq[i], s, ant)) return false
                }
            }
        }
        return true
    }

    /** Cost variant: derive `cost` bounds from the min/max weighted path over the forward lattice. */
    private fun tightenCost(state: PropagationState, ant: IntArray?): Boolean {
        val inf = Long.MAX_VALUE / 4
        val minCost = Array(n + 1) { LongArray(numStatesPerLayer[it]) { inf } }
        val maxCost = Array(n + 1) { LongArray(numStatesPerLayer[it]) { -inf } }
        if (initial in 0 until numStatesPerLayer[0]) {
            minCost[0][initial] = 0L
            maxCost[0][initial] = 0L
        }
        for (i in 0 until n) {
            val d = state.intDomains[seq[i]]
            val numI = numStatesPerLayer[i]
            val numN = numStatesPerLayer[i + 1]
            var p = layerStarts[i]
            val end = layerStarts[i + 1]
            while (p < end) {
                val src = transitions[p]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2]
                val ww = transitions[p + 3].toLong()
                if (sym in d.min..d.max && src in 0 until numI && dst in 0 until numN &&
                    testBit(fwd, i, src) && testBit(fwd, i + 1, dst)
                ) {
                    val nm = minCost[i][src] + ww
                    if (nm < minCost[i + 1][dst]) minCost[i + 1][dst] = nm
                    val nM = maxCost[i][src] + ww
                    if (nM > maxCost[i + 1][dst]) maxCost[i + 1][dst] = nM
                }
                p += recordStride
            }
        }
        var bestLo = inf
        var bestHi = -inf
        val numN = numStatesPerLayer[n]
        for (s in accepting) {
            if (s in 0 until numN && testBit(fwd, n, s)) {
                if (minCost[n][s] < bestLo) bestLo = minCost[n][s]
                if (maxCost[n][s] > bestHi) bestHi = maxCost[n][s]
            }
        }
        if (bestLo == inf) return false
        if (bestLo > Int.MAX_VALUE.toLong()) return false
        if (bestHi < Int.MIN_VALUE.toLong()) return false
        val loBound = if (bestLo < Int.MIN_VALUE.toLong()) Int.MIN_VALUE else bestLo.toInt()
        val hiBound = if (bestHi > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else bestHi.toInt()
        if (!state.tightenIntMin(cost, loBound, ant)) return false
        if (!state.tightenIntMax(cost, hiBound, ant)) return false
        return true
    }

    fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (initial < 0 || initial >= numStatesPerLayer[0]) return false
        val dirty = state.drainIntEventDirtyVars(factorId)
        if (valid.value == 1 && dirty.isEmpty()) return true
        val ant = state.composeIntVarAtomAntecedents(if (cost >= 0) seq + intArrayOf(cost) else seq)
        return if (valid.value == 0) rebuild(state, ant) else delta(state, dirty, ant)
    }

    private fun rebuild(state: PropagationState, ant: IntArray?): Boolean {
        val scratch = LongArray(w)
        for (k in 0 until (n + 1) * w) {
            fwd[k] = 0L
            bwd[k] = 0L
        }
        scratch[initial ushr 6] = 1L shl (initial and 63)
        writeLayer(fwd, 0, scratch)
        for (i in 0 until n) {
            if (layerEmpty(fwd, i)) return false
            recomputeForward(state, i, scratch)
        }
        if (!anyAcceptingForward()) return false
        recomputeAcceptLayer(scratch)
        for (i in n - 1 downTo 0) recomputeBackward(state, i, scratch)
        valid.set(1)
        if (!prune(state, 0, n - 1, ant)) return false
        if (cost >= 0 && !tightenCost(state, ant)) return false
        return true
    }

    private fun delta(state: PropagationState, dirty: IntArray, ant: IntArray?): Boolean {
        var minD = n
        var maxD = -1
        for (i in seq.indices) {
            val v = seq[i]
            for (d in dirty) {
                if (d == v) {
                    if (i < minD) minD = i
                    if (i > maxD) maxD = i
                    break
                }
            }
        }
        if (maxD >= 0) {
            val scratch = LongArray(w)
            var lastFwdChanged = minD
            var i = minD
            while (i < n) {
                if (layerEmpty(fwd, i)) return false
                val ch = recomputeForward(state, i, scratch)
                if (ch) lastFwdChanged = i + 1
                if (i >= maxD && !ch) break
                i++
            }
            if (layerEmpty(fwd, lastFwdChanged) || !anyAcceptingForward()) return false
            var bwdStart = maxOf(maxD, lastFwdChanged)
            var firstBwdChanged = bwdStart
            if (bwdStart == n) {
                if (recomputeAcceptLayer(scratch)) firstBwdChanged = n
                bwdStart = n - 1
            }
            var j = bwdStart
            while (j >= 0) {
                val ch = recomputeBackward(state, j, scratch)
                if (ch) firstBwdChanged = j
                if (j <= minD && !ch) break
                j--
            }
            val lo = maxOf(0, minOf(minD, firstBwdChanged - 1))
            val hi = minOf(n - 1, maxOf(maxD, lastFwdChanged))
            if (!prune(state, lo, hi, ant)) return false
        }
        // Cost is one-directional output (path bounds over the forward lattice); recompute whenever
        // anything changed — cheap relative to the reachability cascade and only for cost MDDs.
        if (cost >= 0 && !tightenCost(state, ant)) return false
        return true
    }
}
