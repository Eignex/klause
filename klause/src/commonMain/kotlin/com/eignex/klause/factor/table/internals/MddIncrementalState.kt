package com.eignex.klause.factor.table.internals

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.propagation.ExtensionalSupportCounts
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.RevLongArray
import com.eignex.klause.propagation.checkFiniteWork
import com.eignex.klause.propagation.lazyReason
import com.eignex.util.arrays.binarySearchLong

// Reversible, delta-driven layered-MDD filtering. Each layer keeps forward and backward reachability; a symbol at
// position i survives iff a forward-reachable state transitions on it to a backward-reachable state. A fire
// recomputes affected layers and backtracking restores changed words. Sweeps visit only edges of reachable states.
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

    // Each layer's bitset is packed to its own width: middle layers of word MDDs are far wider than the ends, and
    // a uniform width would multiply memory per factor. [layerBase] is each layer's word offset into [fwd]/[bwd].
    private val layerWords = IntArray(seq.size + 1) { (numStatesPerLayer[it] + 63) ushr 6 }
    private val layerBase = IntArray(seq.size + 2).also { for (i in 0..seq.size) it[i + 1] = it[i] + layerWords[i] }
    private val totalWords = layerBase[seq.size + 1]
    private val w = layerWords.max()

    // Held for the deferred [fwd]/[bwd] construction below; every other reversible cell is built here.
    private val owner = state

    // Built on first real use: a row that only reuses the shared root snapshot never reads them, and they are large
    // on wide diagrams. Single-threaded per [PropagationState], hence the unsynchronized mode. See [snapshotOnly].
    private val fwdLazy = lazy(LazyThreadSafetyMode.NONE) { RevLongArray(owner, totalWords) }
    private val bwdLazy = lazy(LazyThreadSafetyMode.NONE) { RevLongArray(owner, totalWords) }
    private val fwd: RevLongArray get() = fwdLazy.value
    private val bwd: RevLongArray get() = bwdLazy.value

    internal val layersBuilt: Boolean get() = fwdLazy.isInitialized()
    private val valid = RevInt(state, 0)

    fun invalidate() {
        valid.set(0)
    }

    // 1 while this factor's reachability is exactly the shared root snapshot and [fwd]/[bwd] hold nothing of its
    // own. Reversible, so a backtrack past the fill restores 1 and the stale bitsets are re-seeded before use.
    private val snapshotOnly = RevInt(state, 0)

    // Shared across factors over one diagram when the caller passes one in.
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
        (rev[layerBase[layer] + (s ushr 6)] and (1L shl (s and 63))) != 0L

    private inline fun forEachState(rev: RevLongArray, layer: Int, cap: Int, action: (Int) -> Unit) {
        val base = layerBase[layer]
        for (k in 0 until layerWords[layer]) {
            owner.checkFiniteWork()
            var word = rev[base + k]
            while (word != 0L) {
                owner.checkFiniteWork()
                val s = (k shl 6) + word.countTrailingZeroBits()
                if (s < cap) action(s)
                word = word and (word - 1)
            }
        }
    }

    private fun layerEmpty(rev: RevLongArray, layer: Int): Boolean {
        val base = layerBase[layer]
        for (k in 0 until layerWords[layer]) if (rev[base + k] != 0L) return false
        return true
    }

    private fun writeLayer(rev: RevLongArray, layer: Int, tmp: LongArray): Boolean {
        val base = layerBase[layer]
        var changed = false
        for (k in 0 until layerWords[layer]) {
            owner.checkFiniteWork()
            if (rev[base + k] != tmp[k]) {
                rev[base + k] = tmp[k]
                changed = true
            }
        }
        return changed
    }

    // fwd(i+1) = { dst : (src,sym,dst) ∈ layer i, src ∈ fwd(i), sym ∈ domain(seq(i)) }
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
                owner.checkFiniteWork()
                val p = ptr[k]
                val sym = transitions[p + 1]
                val dst = transitions[p + 2].toInt()
                if (sym in d && dst in 0 until numN) {
                    scratch[dst ushr 6] = scratch[dst ushr 6] or (1L shl (dst and 63))
                }
                k++
            }
        }
        return writeLayer(fwd, i + 1, scratch)
    }

    // bwd(i) = { src ∈ fwd(i) : (src,sym,dst) ∈ layer i, dst ∈ bwd(i+1), sym ∈ domain(seq(i)) }
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
                owner.checkFiniteWork()
                val src = srcs[k]
                val sym = syms[k]
                if (sym in d && src in 0 until numI && testBit(fwd, i, src)) {
                    scratch[src ushr 6] = scratch[src ushr 6] or (1L shl (src and 63))
                }
                k++
            }
        }
        return writeLayer(bwd, i, scratch)
    }

    // Also records into [survives] the symbols that keep position `i` alive, which are exactly the edges this scan
    // keeps, so the diagram needs no third scan for pruning. [survives] is indexed by `sym - minSym`, so it can
    // seed the shared root snapshot, and must be pre-zeroed.
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
                owner.checkFiniteWork()
                val src = srcs[k]
                val sym = syms[k]
                if (sym in d && src in 0 until numI && testBit(fwd, i, src)) {
                    scratch[src ushr 6] = scratch[src ushr 6] or (1L shl (src and 63))
                    val off = (sym - minSym).toInt()
                    survives[off ushr 6] = survives[off ushr 6] or (1L shl (off and 63))
                }
                k++
            }
        }
        writeLayer(bwd, i, scratch)
    }

    // Full alphabets make reachability purely structural, so it can be shared through the root snapshot.
    private fun domainsCoverAlphabet(state: PropagationState): Boolean {
        if (symSpan <= 0) return false
        val maxSym = minSym + symSpan - 1
        for (i in 0 until n) {
            owner.checkFiniteWork()
            val d = state.intDomains[seq[i]]
            if (d.min > minSym || d.max < maxSym) return false
            if (d.holeCount == 0L) continue
            if (minSym !in d || maxSym !in d) return false
            if (d.withMinAtLeast(minSym).withMaxAtMost(maxSym).holeCount != 0L) return false
        }
        return true
    }

    private fun excludeNonSurvivors(state: PropagationState, i: Int, survives: LongArray, ant: IntArray?): Boolean {
        val d = state.intDomains[seq[i]]
        for (s in d.min..d.max) {
            owner.checkFiniteWork()
            val off = s - minSym
            val alive = off in 0 until symSpan &&
                ((survives[(off ushr 6).toInt()] ushr (off and 63L).toInt()) and 1L) != 0L
            if (!alive && !state.excludeIntValue(seq[i], s, reason(state, PRUNE, i, ant, s))) return false
        }
        return true
    }

    // bwd(n) = accepting ∩ fwd(n)
    private fun recomputeAcceptLayer(scratch: LongArray): Boolean {
        scratch.fill(0L)
        val numN = numStatesPerLayer[n]
        for (s in accepting) {
            owner.checkFiniteWork()
            if (s in 0 until numN && testBit(fwd, n, s)) scratch[s ushr 6] = scratch[s ushr 6] or (1L shl (s and 63))
        }
        return writeLayer(bwd, n, scratch)
    }

    private fun anyAcceptingForward(): Boolean {
        val numN = numStatesPerLayer[n]
        for (s in accepting) if (s in 0 until numN && testBit(fwd, n, s)) return true
        return false
    }

    private fun prune(state: PropagationState, lo: Int, hi: Int, ant: IntArray?): Boolean {
        for (i in lo..hi) {
            owner.checkFiniteWork()
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
                    owner.checkFiniteWork()
                    val p = ptr[k]
                    val sym = transitions[p + 1]
                    val dst = transitions[p + 2].toInt()
                    if (sym in d && dst in 0 until numN && testBit(bwd, i + 1, dst)) {
                        val off = sym - d.min
                        survives[(off ushr 6).toInt()] =
                            survives[(off ushr 6).toInt()] or (1L shl (off and 63L).toInt())
                    }
                    k++
                }
            }
            for (s in d.min..d.max) {
                owner.checkFiniteWork()
                val off = s - d.min
                if (((survives[(off ushr 6).toInt()] ushr (off and 63L).toInt()) and 1L) == 0L) {
                    if (!state.excludeIntValue(seq[i], s, reason(state, PRUNE, i, ant, s))) return false
                }
            }
        }
        return true
    }

    // Cost bounds from the min/max weighted path over the forward lattice.
    private fun tightenCost(state: PropagationState, ant: IntArray?): Boolean {
        val inf = Long.MAX_VALUE / 4
        val minCost = Array(n + 1) { LongArray(numStatesPerLayer[it]) { inf } }
        val maxCost = Array(n + 1) { LongArray(numStatesPerLayer[it]) { -inf } }
        if (initial in 0 until numStatesPerLayer[0]) {
            minCost[0][initial] = 0L
            maxCost[0][initial] = 0L
        }
        for (i in 0 until n) {
            owner.checkFiniteWork()
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
                    owner.checkFiniteWork()
                    val p = ptr[k]
                    val sym = transitions[p + 1]
                    val dst = transitions[p + 2].toInt()
                    val ww = transitions[p + 3]
                    if (sym in d && dst in 0 until numN && testBit(fwd, i + 1, dst)) {
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
            owner.checkFiniteWork()
            if (s in 0 until numN && testBit(fwd, n, s)) {
                if (minCost[n][s] < bestLo) bestLo = minCost[n][s]
                if (maxCost[n][s] > bestHi) bestHi = maxCost[n][s]
            }
        }
        if (bestLo == inf) return false
        if (!state.tightenIntMin(cost, bestLo, reason(state, COST, 1, ant))) return false
        if (!state.tightenIntMax(cost, bestHi, reason(state, COST, 0, ant))) return false
        return true
    }

    // A pruned position's reason, or the cost bound's (argument 1 for the lower bound), recorded lazily.
    private fun reason(
        state: PropagationState,
        kind: Int,
        arg: Int,
        coarse: IntArray?,
        value: Long? = null,
    ): IntArray? = when {
        state.currentLevel == 0 -> null
        state.undoLogging -> state.lazyReason(
            if (value == null) intArrayOf(kind, arg) else {
                intArrayOf(kind, arg, (value ushr 32).toInt(), value.toInt())
            },
        )
        else -> coarse
    }

    private var pathWeights: MddPathWeights? = null
    private var pathWeightsAttempted = false

    fun supportCounts(variable: Int, values: LongArray, visit: () -> Boolean): ExtensionalSupportCounts? {
        if (valid.value == 0) return null
        var pos = -1
        for (i in seq.indices) {
            if (!visit()) return null
            if (seq[i] == variable) {
                pos = i
                break
            }
        }
        if (pos < 0) return null
        if (!pathWeightsAttempted && cost < 0) {
            pathWeights = MddPathWeights.build(
                owner, seq, numStatesPerLayer, transitions, idx, initial, accepting, visit,
            )
            pathWeightsAttempted = true
        }
        pathWeights?.supportCounts(owner, pos, values, visit)?.let { return it }
        val snapshot = if (snapshotOnly.value == 1) idx.rootSnapshot ?: return null else null
        val counts = IntArray(values.size)
        val domain = owner.intDomains[seq[pos]]
        val head = fwdHead[pos]
        val ptr = fwdPtr[pos]
        var total = 0
        for (k in 0 until layerWords[pos]) {
            if (!visit()) return null
            var word = if (snapshot == null) fwd[layerBase[pos] + k] else snapshot.fwd[layerBase[pos] + k]
            while (word != 0L) {
                val src = (k shl 6) + word.countTrailingZeroBits()
                word = word and (word - 1)
                if (src >= numStatesPerLayer[pos]) continue
                for (edge in head[src] until head[src + 1]) {
                    if (!visit()) return null
                    val p = ptr[edge]
                    val sym = transitions[p + 1]
                    val dst = transitions[p + 2].toInt()
                    if (sym !in domain || dst !in 0 until numStatesPerLayer[pos + 1]) continue
                    val slot = layerBase[pos + 1] + (dst ushr 6)
                    val backward = if (snapshot == null) bwd[slot] else snapshot.bwd[slot]
                    if (backward and (1L shl (dst and 63)) == 0L) continue
                    total++
                    val candidate = values.binarySearchLong(sym)
                    if (candidate >= 0) counts[candidate]++
                }
            }
        }
        return if (total == 0) null else ExtensionalSupportCounts(counts, total)
    }

    fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (initial < 0 || initial >= numStatesPerLayer[0]) return false
        val dirty = state.drainIntEventDirtyVars(factorId)
        if (valid.value == 1 && dirty.isEmpty()) return true
        // With the undo log each deduction's reason is built only if analysis reads it ([MddExplainer]); without
        // one every variable's holes and bounds stand in.
        val ant = if (state.undoLogging) {
            null
        } else {
            collectHoleAndBoundAntecedents(state, if (cost >= 0) seq + intArrayOf(cost) else seq)
        }
        if (valid.value == 0) return rebuild(state, ant)
        // The incremental step rewrites the bitsets, so a factor on the shared snapshot copies it first.
        if (snapshotOnly.value == 1 && !seedFromSnapshot()) return computeReachability(state, ant, snapshot = false)
        return delta(state, dirty, ant)
    }

    private fun rebuild(state: PropagationState, ant: IntArray?): Boolean {
        // Factors over one diagram compute structural reachability once: otherwise every factor repeats the
        // dominant sweep at the root.
        if (domainsCoverAlphabet(state)) {
            idx.rootSnapshot?.let { return applyRootSnapshot(state, it, ant) }
            return computeReachability(state, ant, snapshot = true)
        }
        return computeReachability(state, ant, snapshot = false)
    }

    private fun computeReachability(state: PropagationState, ant: IntArray?, snapshot: Boolean): Boolean {
        val scratch = LongArray(w)
        for (k in 0 until totalWords) {
            owner.checkFiniteWork()
            fwd[k] = 0L
            bwd[k] = 0L
        }
        scratch[initial ushr 6] = 1L shl (initial and 63)
        writeLayer(fwd, 0, scratch)
        for (i in 0 until n) {
            owner.checkFiniteWork()
            if (layerEmpty(fwd, i)) return false
            recomputeForward(state, i, scratch)
        }
        if (!anyAcceptingForward()) return false
        recomputeAcceptLayer(scratch)
        // Exclusions are applied after the fused backward sweep, so every backward pass reads the pre-prune domains.
        val survivors = Array(n) { LongArray(symWords.coerceAtLeast(1)) }
        for (i in n - 1 downTo 0) recomputeBackwardCollecting(state, i, scratch, survivors[i])
        valid.set(1)
        snapshotOnly.set(0)
        if (snapshot) {
            idx.rootSnapshot = MddRootSnapshot(
                LongArray(totalWords) { fwd[it] },
                LongArray(totalWords) { bwd[it] },
                survivors,
            )
        }
        for (i in 0 until n) if (!excludeNonSurvivors(state, i, survivors[i], ant)) return false
        if (cost >= 0 && !tightenCost(state, ant)) return false
        return true
    }

    private fun applyRootSnapshot(state: PropagationState, snap: MddRootSnapshot, ant: IntArray?): Boolean {
        // The snapshot is this factor's reachability, so [fwd]/[bwd] stay unbuilt until an incremental step needs
        // them. A cost diagram copies now, since its bound reads the forward lattice.
        if (cost >= 0) seedFrom(snap) else snapshotOnly.set(1)
        valid.set(1)
        for (i in 0 until n) if (!excludeNonSurvivors(state, i, snap.survivors[i], ant)) return false
        if (cost >= 0 && !tightenCost(state, ant)) return false
        return true
    }

    // False when the snapshot is gone, leaving the caller to recompute.
    private fun seedFromSnapshot(): Boolean {
        val snap = idx.rootSnapshot ?: return false
        seedFrom(snap)
        return true
    }

    private fun seedFrom(snap: MddRootSnapshot) {
        val f = fwd
        val b = bwd
        for (k in 0 until totalWords) {
            owner.checkFiniteWork()
            f[k] = snap.fwd[k]
            b[k] = snap.bwd[k]
        }
        snapshotOnly.set(0)
    }

    private fun delta(state: PropagationState, dirty: IntArray, ant: IntArray?): Boolean {
        var minD = n
        var maxD = -1
        for (i in seq.indices) {
            owner.checkFiniteWork()
            val v = seq[i]
            for (d in dirty) {
                owner.checkFiniteWork()
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
                owner.checkFiniteWork()
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
                owner.checkFiniteWork()
                val ch = recomputeBackward(state, j, scratch)
                if (ch) firstBwdChanged = j
                if (j <= minD && !ch) break
                j--
            }
            val lo = maxOf(0, minOf(minD, firstBwdChanged - 1))
            val hi = minOf(n - 1, maxOf(maxD, lastFwdChanged))
            if (!prune(state, lo, hi, ant)) return false
        }
        // Cheap relative to the reachability cascade, so cost bounds are recomputed on every change.
        if (cost >= 0 && !tightenCost(state, ant)) return false
        return true
    }

    internal companion object {
        const val PRUNE = 0
        const val COST = 1
    }
}
