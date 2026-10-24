package com.eignex.klause.factor.table.internals

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.ExtensionalSupportCounts
import com.eignex.klause.propagation.checkFiniteWork
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.RevLongArray
import com.eignex.klause.propagation.lazyReason
import com.eignex.klause.util.binarySearchLong

/*
 * Reversible, delta-driven layered-MDD filtering. Each layer keeps forward reachability from
 * [initial] and backward reachability to accepting states in [RevLongArray]. A symbol at
 * position i survives iff a forward-reachable state transitions on it to a backward-reachable
 * state. Reachability filters full symbol domains, including interior holes. A fire
 * recomputes affected layers; backtracking restores changed words. The cost variant derives
 * cost-variable bounds from the current forward lattice.
 *
 * Transitions are indexed by source ([fwdHead], [fwdPtr]) and destination ([bwdHead], [bwdSrc],
 * [bwdSym]). Sweeps visit outgoing edges of reachable sources or incoming edges of reachable
 * destinations, limiting work to live edges.
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

    // Each layer's reachability bitset is packed to its OWN width (`ceil(numStatesPerLayer / 64)` words)
    // rather than a uniform `max(numStatesPerLayer)` width. A layered diagram whose middle layers are far
    // wider than its ends (word MDDs) would otherwise pay the widest layer for every layer — a several-fold
    // blow-up per factor, decisive when a group holds thousands of factors over one diagram. [layerBase] is the
    // prefix-sum word offset of each layer into [fwd]/[bwd]; [layerWords] its word count; [w] the widest
    // layer's word count (scratch size).
    private val layerWords = IntArray(seq.size + 1) { (numStatesPerLayer[it] + 63) ushr 6 }
    private val layerBase = IntArray(seq.size + 2).also { for (i in 0..seq.size) it[i + 1] = it[i] + layerWords[i] }
    private val totalWords = layerBase[seq.size + 1]
    private val w = layerWords.max()

    // Held for the deferred [fwd]/[bwd] construction below; every other reversible cell is built here.
    private val owner = state

    // Two `totalWords`-long reversible bitsets per factor — on a wide diagram of millions of states
    // that is hundreds of KB each, and a `<group>` holds one factor per row over the same diagram. A row
    // that only ever reuses the shared root snapshot never reads them, so they are built on first real
    // use rather than at construction. Single-threaded per [PropagationState], hence the unsynchronized
    // mode. See [snapshotOnly] for what keeps a deferred pair from being read stale.
    private val fwdLazy = lazy(LazyThreadSafetyMode.NONE) { RevLongArray(owner, totalWords) }
    private val bwdLazy = lazy(LazyThreadSafetyMode.NONE) { RevLongArray(owner, totalWords) }
    private val fwd: RevLongArray get() = fwdLazy.value
    private val bwd: RevLongArray get() = bwdLazy.value

    /** Whether this factor has had to build its own reachability bitsets — false while it is still
     *  standing on the group's shared root snapshot. */
    internal val layersBuilt: Boolean get() = fwdLazy.isInitialized() || compact != null
    private val valid = RevInt(state, 0)
    private var compact: CompactMddState? = null
    private val compactEligible = cost < 0 && seq.all { state.rootDomains[it].spanOrNull(4096) != null } &&
        numStatesPerLayer.sumOf { it.toLong() } * 8 +
        (transitions.size.toLong() / recordStride / 64 + seq.size) * 12 <= ExtensionalSupportIndex.MAX_BYTES

    fun invalidate() {
        valid.set(0)
        compact = null
    }

    /** 1 while this factor's reachability is exactly the shared root snapshot and [fwd]/[bwd] therefore
     *  hold nothing of its own. Reversible alongside [valid], so a backtrack above the point where the
     *  layers were filled restores it to 1 and the stale bitsets are re-seeded before any incremental
     *  step reads them. */
    private val snapshotOnly = RevInt(state, 0)

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
        (rev[layerBase[layer] + (s ushr 6)] and (1L shl (s and 63))) != 0L

    /** Invoke [action] for each set bit below [cap] in [rev]'s [layer] words (the live states). */
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

    /** fwd(i+1) = { dst : (src,sym,dst) ∈ layer i, src ∈ fwd(i), sym ∈ domain(seq(i)) }. */
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

    /** bwd(i) = { src ∈ fwd(i) : (src,sym,dst) ∈ layer i, dst ∈ bwd(i+1), sym ∈ domain(seq(i)) }. */
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

    /** Whether every position still admits the whole alphabet, so the reachability is purely structural
     *  and can be shared through the root snapshot. */
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

    /** Exclude, at position [i], every live domain value that no surviving edge keeps (its alphabet bit is
     *  clear, or it lies outside the alphabet entirely). Shared by the compute and snapshot-reuse paths. */
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

    /** bwd(n) = accepting ∩ fwd(n). */
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

    /** Prune every position in `[lo, hi]`: a symbol value survives iff some forward-reachable state
     *  transitions on it to a backward-co-reachable state. Returns false on a domain wipeout. */
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

    fun supportCounts(variable: Int, values: LongArray, visit: () -> Boolean): ExtensionalSupportCounts? {
        compact?.let { return it.supportCounts(variable, values, visit) }
        if (valid.value == 0) return null
        val pos = seq.indexOf(variable)
        if (pos < 0) return null
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
        if (state.undoLogging && compactEligible) {
            val candidate = compact ?: idx.compactSupports(transitions, state::checkFiniteWork)?.let { supports ->
                CompactMddState(state, seq, numStatesPerLayer, transitions, initial, accepting, fwdPtr, supports)
                    .also { compact = it }
            }
            if (candidate != null) return candidate.propagate()
        }
        if (valid.value == 1 && dirty.isEmpty()) return true
        // With the undo log each deduction's reason is built only if analysis reads it ([MddExplainer]); without
        // one every variable's holes and bounds stand in.
        val ant = if (state.undoLogging) {
            null
        } else {
            collectHoleAndBoundAntecedents(state, if (cost >= 0) seq + intArrayOf(cost) else seq)
        }
        if (valid.value == 0) return rebuild(state, ant)
        // Standing on the shared snapshot: take a private copy of it first, since the incremental step
        // reads and rewrites the bitsets. A vanished snapshot leaves nothing to step from, so recompute.
        if (snapshotOnly.value == 1 && !seedFromSnapshot()) return computeReachability(state, ant, snapshot = false)
        return delta(state, dirty, ant)
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
        // Fuse the backward reachability sweep with pruning: the surviving symbols per position fall out of
        // the same accepting-to-root scan, so the diagram needs no separate full prune pass. Survivors are
        // collected per layer and the (scan-free) exclusions applied afterwards, so every backward pass
        // still reads the pre-prune domains — identical to a backward-then-prune ordering.
        val survivors = Array(n) { LongArray(symWords.coerceAtLeast(1)) }
        for (i in n - 1 downTo 0) recomputeBackwardCollecting(state, i, scratch, survivors[i])
        valid.set(1)
        // The bitsets now hold this factor's own reachability, not the shared snapshot's.
        snapshotOnly.set(0)
        // Under full domains the survivors are structural; publish them (and the reachability) once for the
        // group's remaining factors to reuse.
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
        // The snapshot IS this factor's reachability, so copying it into per-factor bitsets would only
        // duplicate it. Record that instead and leave [fwd]/[bwd] unbuilt; [seedFromSnapshot] fills them
        // if and when an incremental step actually needs them. A cost diagram is the exception: its bound
        // comes from reading the forward lattice below, so it takes its copy now.
        if (cost >= 0) seedFrom(snap) else snapshotOnly.set(1)
        valid.set(1)
        for (i in 0 until n) if (!excludeNonSurvivors(state, i, snap.survivors[i], ant)) return false
        if (cost >= 0 && !tightenCost(state, ant)) return false
        return true
    }

    /** Fill [fwd]/[bwd] from the shared root snapshot this factor has been standing on, so an incremental
     *  step can proceed from its own copy. Returns false when the snapshot is gone, leaving the caller to
     *  recompute from scratch. */
    private fun seedFromSnapshot(): Boolean {
        val snap = idx.rootSnapshot ?: return false
        seedFrom(snap)
        return true
    }

    /** Copy [snap] into this factor's own bitsets, leaving it free to diverge. */
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
        // Cost is one-directional output (path bounds over the forward lattice); recompute whenever
        // anything changed — cheap relative to the reachability cascade and only for cost MDDs.
        if (cost >= 0 && !tightenCost(state, ant)) return false
        return true
    }

    internal companion object {
        const val PRUNE = 0
        const val COST = 1
    }
}
