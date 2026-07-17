package com.eignex.klause.factor.table.internals

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.RevLongArray

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
 * Each layer's transition records are indexed by source state (a per-layer CSR: [fwdHead] offsets into
 * [fwdPtr]) and by destination state ([bwdHead] into the inline [bwdSrc]/[bwdSym]). The forward, prune,
 * and cost sweeps then visit only the outgoing records of forward-reachable sources, and the backward
 * sweep only the incoming records of co-reachable destinations — O(live out-edges) rather than O(all
 * records per layer). Sparse diagrams (few reachable states per layer) gain the most; a dense diagram
 * does no less work than before.
 *
 * Soundness gated by MddPropagatorTest enumerate-vs-brute under full CDCL across deep backtracking.
 */
internal class MddIncrementalState(
    state: PropagationState,
    private val seq: IntArray,
    private val numStatesPerLayer: IntArray,
    private val layerStarts: IntArray,
    private val transitions: LongArray,
    private val initial: Int,
    private val accepting: IntArray,
    private val recordStride: Int,
    private val cost: Int,
    index: MddTransitionIndex? = null,
) {
    private val n = seq.size
    private val maxStates = numStatesPerLayer.max()
    private val w = (maxStates + 63) ushr 6

    private val fwd = RevLongArray(state, (n + 1) * w)
    private val bwd = RevLongArray(state, (n + 1) * w)
    private val valid = RevInt(state, 0)

    // Per-layer CSR indices over the transition records (see [MddTransitionIndex]). Shared across the
    // factors of a `<group>` of identical diagrams when the caller passes one in; otherwise built here.
    private val idx = index ?: MddTransitionIndex.build(transitions, layerStarts, numStatesPerLayer, recordStride)
    private val fwdHead = idx.fwdHead
    private val fwdPtr = idx.fwdPtr
    private val bwdHead = idx.bwdHead
    private val bwdSrc = idx.bwdSrc
    private val bwdSym = idx.bwdSym

    // The diagram's alphabet [minSym, maxSym]; survivor bitsets and the shared root snapshot are keyed by
    // `sym - minSym`. `symSpan <= 0` marks an edgeless diagram (no sharing).
    private val minSym = idx.minSym
    private val symSpan = idx.maxSym - idx.minSym + 1
    private val symWords = if (symSpan > 0) ((symSpan + 63) ushr 6).toInt() else 0

    private fun testBit(rev: RevLongArray, layer: Int, s: Int): Boolean =
        (rev[layer * w + (s ushr 6)] and (1L shl (s and 63))) != 0L

    /** Invoke [action] for each set bit below [cap] in [rev]'s [layer] words (the live states). */
    private inline fun forEachState(rev: RevLongArray, layer: Int, cap: Int, action: (Int) -> Unit) {
        val base = layer * w
        for (k in 0 until w) {
            var word = rev[base + k]
            while (word != 0L) {
                val s = (k shl 6) + word.countTrailingZeroBits()
                if (s < cap) action(s)
                word = word and (word - 1)
            }
        }
    }

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
        val numN = numStatesPerLayer[i + 1]
        val head = fwdHead[i]
        val ptr = fwdPtr[i]
        forEachState(fwd, i, numStatesPerLayer[i]) { src ->
            var k = head[src]
            val e = head[src + 1]
            while (k < e) {
                val p = ptr[k]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2].toInt()
                if (sym in d.min..d.max && dst in 0 until numN) {
                    scratch[dst ushr 6] = scratch[dst ushr 6] or (1L shl (dst and 63))
                }
                k++
            }
        }
        return writeLayer(fwd, i + 1, scratch)
    }

    /** bwd[i] = { src ∈ fwd[i] : (src,sym,dst) ∈ layer i, dst ∈ bwd[i+1], sym ∈ [min,max](seq[i]) }. */
    private fun recomputeBackward(state: PropagationState, i: Int, scratch: LongArray): Boolean {
        scratch.fill(0L)
        val d = state.intDomains[seq[i]]
        val numI = numStatesPerLayer[i]
        val head = bwdHead[i]
        val srcs = bwdSrc[i]
        val syms = bwdSym[i]
        forEachState(bwd, i + 1, numStatesPerLayer[i + 1]) { dst ->
            var k = head[dst]
            val e = head[dst + 1]
            while (k < e) {
                val src = srcs[k]
                val sym = syms[k]
                if (sym in d.min..d.max && src in 0 until numI && testBit(fwd, i, src)) {
                    scratch[src ushr 6] = scratch[src ushr 6] or (1L shl (src and 63))
                }
                k++
            }
        }
        return writeLayer(bwd, i, scratch)
    }

    /**
     * Backward sweep for the full [rebuild] that also records, into [survives], which symbols keep
     * position `i` alive: a symbol survives iff some incoming edge on it joins a forward-reachable source
     * to a backward-co-reachable destination — precisely the edges this scan already keeps. The [rebuild]
     * then excludes the non-survivors from a cheap per-position loop, so the diagram is never scanned a
     * third time for a separate prune pass. [survives] is indexed by `sym - minSym` (the alphabet offset,
     * so the same bitset can seed the shared root snapshot) and must be pre-zeroed.
     */
    private fun recomputeBackwardCollecting(state: PropagationState, i: Int, scratch: LongArray, survives: LongArray) {
        scratch.fill(0L)
        val d = state.intDomains[seq[i]]
        val numI = numStatesPerLayer[i]
        val head = bwdHead[i]
        val srcs = bwdSrc[i]
        val syms = bwdSym[i]
        forEachState(bwd, i + 1, numStatesPerLayer[i + 1]) { dst ->
            var k = head[dst]
            val e = head[dst + 1]
            while (k < e) {
                val src = srcs[k]
                val sym = syms[k]
                if (sym in d.min..d.max && src in 0 until numI && testBit(fwd, i, src)) {
                    scratch[src ushr 6] = scratch[src ushr 6] or (1L shl (src and 63))
                    val off = (sym - minSym).toInt()
                    survives[off ushr 6] = survives[off ushr 6] or (1L shl (off and 63))
                }
                k++
            }
        }
        writeLayer(bwd, i, scratch)
    }

    /** Whether every position still admits the whole alphabet, so the reachability is purely structural
     *  and can be shared through the root snapshot. */
    private fun domainsCoverAlphabet(state: PropagationState): Boolean {
        if (symSpan <= 0) return false
        for (i in 0 until n) {
            val d = state.intDomains[seq[i]]
            if (d.min > minSym || d.max < minSym + symSpan - 1) return false
        }
        return true
    }

    /** Exclude, at position [i], every live domain value that no surviving edge keeps (its alphabet bit is
     *  clear, or it lies outside the alphabet entirely). Shared by the compute and snapshot-reuse paths. */
    private fun excludeNonSurvivors(state: PropagationState, i: Int, survives: LongArray, ant: IntArray?): Boolean {
        val d = state.intDomains[seq[i]]
        for (s in d.min..d.max) {
            val off = s - minSym
            val alive = off in 0 until symSpan &&
                ((survives[(off ushr 6).toInt()] ushr (off and 63L).toInt()) and 1L) != 0L
            if (!alive && !state.excludeIntValue(seq[i], s, ant)) return false
        }
        return true
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
            val survives = LongArray(((span + 63) ushr 6).toInt())
            val numN = numStatesPerLayer[i + 1]
            val head = fwdHead[i]
            val ptr = fwdPtr[i]
            forEachState(fwd, i, numStatesPerLayer[i]) { src ->
                var k = head[src]
                val e = head[src + 1]
                while (k < e) {
                    val p = ptr[k]
                    val sym = transitions[p + 1]
                    val dst = transitions[p + 2].toInt()
                    if (sym in d.min..d.max && dst in 0 until numN && testBit(bwd, i + 1, dst)) {
                        val off = sym - d.min
                        survives[(off ushr 6).toInt()] =
                            survives[(off ushr 6).toInt()] or (1L shl (off and 63L).toInt())
                    }
                    k++
                }
            }
            for (s in d.min..d.max) {
                val off = s - d.min
                if (((survives[(off ushr 6).toInt()] ushr (off and 63L).toInt()) and 1L) == 0L) {
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
            val numN = numStatesPerLayer[i + 1]
            val head = fwdHead[i]
            val ptr = fwdPtr[i]
            forEachState(fwd, i, numStatesPerLayer[i]) { src ->
                val srcMin = minCost[i][src]
                val srcMax = maxCost[i][src]
                var k = head[src]
                val e = head[src + 1]
                while (k < e) {
                    val p = ptr[k]
                    val sym = transitions[p + 1]
                    val dst = transitions[p + 2].toInt()
                    val ww = transitions[p + 3]
                    if (sym in d.min..d.max && dst in 0 until numN && testBit(fwd, i + 1, dst)) {
                        val nm = srcMin + ww
                        if (nm < minCost[i + 1][dst]) minCost[i + 1][dst] = nm
                        val nM = srcMax + ww
                        if (nM > maxCost[i + 1][dst]) maxCost[i + 1][dst] = nM
                    }
                    k++
                }
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
        if (!state.tightenIntMin(cost, bestLo, ant)) return false
        if (!state.tightenIntMax(cost, bestHi, ant)) return false
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
        // When every position still admits the whole alphabet the reachability is purely structural, so a
        // `<group>` of identical diagrams computes it once and the rest reuse it — the dominant cost on a
        // large shared diagram is this per-factor sweep, and at the root every factor would repeat it.
        if (domainsCoverAlphabet(state)) {
            idx.rootSnapshot?.let { return applyRootSnapshot(state, it, ant) }
            return computeReachability(state, ant, snapshot = true)
        }
        return computeReachability(state, ant, snapshot = false)
    }

    private fun computeReachability(state: PropagationState, ant: IntArray?, snapshot: Boolean): Boolean {
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
        // Fuse the backward reachability sweep with pruning: the surviving symbols per position fall out of
        // the same accepting-to-root scan, so the diagram needs no separate full prune pass. Survivors are
        // collected per layer and the (scan-free) exclusions applied afterwards, so every backward pass
        // still reads the pre-prune domains — identical to a backward-then-prune ordering.
        val survivors = Array(n) { LongArray(symWords.coerceAtLeast(1)) }
        for (i in n - 1 downTo 0) recomputeBackwardCollecting(state, i, scratch, survivors[i])
        valid.set(1)
        // Under full domains the survivors are structural; publish them (and the reachability) once for the
        // group's remaining factors to reuse.
        if (snapshot) {
            idx.rootSnapshot = MddRootSnapshot(
                LongArray((n + 1) * w) { fwd[it] },
                LongArray((n + 1) * w) { bwd[it] },
                survivors,
            )
        }
        for (i in 0 until n) if (!excludeNonSurvivors(state, i, survivors[i], ant)) return false
        if (cost >= 0 && !tightenCost(state, ant)) return false
        return true
    }

    private fun applyRootSnapshot(state: PropagationState, snap: MddRootSnapshot, ant: IntArray?): Boolean {
        for (k in 0 until (n + 1) * w) {
            fwd[k] = snap.fwd[k]
            bwd[k] = snap.bwd[k]
        }
        valid.set(1)
        for (i in 0 until n) if (!excludeNonSurvivors(state, i, snap.survivors[i], ant)) return false
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
