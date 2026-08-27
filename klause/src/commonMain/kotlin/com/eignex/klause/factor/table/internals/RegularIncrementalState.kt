package com.eignex.klause.factor.table.internals

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.RevInt
import com.eignex.klause.propagation.RevLongArray
import com.eignex.klause.propagation.excludeIntValues
import com.eignex.klause.ir.values
import com.eignex.klause.util.LongArrayList

/*
 * Reversible, delta-driven GAC for [Regular] — the incremental counterpart to the full
 * forward+backward layered-DAG rebuild in Regular.propagate. The layered GAC keeps, per
 * layer, the set of DFA states forward-reachable from q0 and backward-co-reachable to an accepting
 * state; a symbol at position i survives iff some forward-reachable state at i transitions on it to a
 * backward-co-reachable state at i+1. Both layer-bitset arrays ride the engine undo trail
 * ([RevLongArray]), so a backtrack restores them in O(changed words) instead of triggering a rebuild,
 * and a forward fire recomputes only the layers a changed position actually propagates through (the
 * forward sweep climbs from the lowest dirty position until a layer is unchanged; the backward sweep
 * descends from the deepest changed layer likewise). Soundness is gated by enumerate-vs-brute under
 * full CDCL across deep backtracking (RegularTest) — a wrong reachability bit is an unsound prune.
 */
internal class RegularIncrementalState(
    state: PropagationState,
    private val seq: IntArray,
    private val numStates: Int,
    private val alphabetSize: Int,
    private val transitions: LongArray,
    private val q0: Int,
    private val accepting: IntArray,
) {
    private val n = seq.size
    private val w = (numStates + 63) ushr 6

    // Forward-reachable / backward-co-reachable state bitmasks per layer (n+1 layers of w words),
    // trailed so a backtrack restores them word-by-word.
    private val fwd = RevLongArray(state, (n + 1) * w)
    private val bwd = RevLongArray(state, (n + 1) * w)
    private val valid = RevInt(state, 0)

    /** Responsible `seq` prefix when a forward layer collapsed — advisory, read by conflictReason
     *  immediately before the engine backtracks, so it need not be reversible. */
    var conflictPrefix: IntArray? = null
        private set

    private fun delta(stateQ: Int, symbol: Long): Int {
        if (stateQ < 1 || stateQ > numStates) return 0
        if (symbol < 1 || symbol > alphabetSize) return 0
        return transitions[(stateQ - 1) * alphabetSize + (symbol - 1).toInt()].toInt()
    }

    private fun testBit(rev: RevLongArray, layer: Int, bit: Int): Boolean =
        (rev[layer * w + (bit ushr 6)] and (1L shl (bit and 63))) != 0L

    private inline fun forEachState(rev: RevLongArray, layer: Int, action: (Int) -> Unit) {
        val base = layer * w
        for (k in 0 until w) {
            var word = rev[base + k]
            while (word != 0L) {
                val q = (k shl 6) + word.countTrailingZeroBits() + 1
                if (q <= numStates) action(q)
                word = word and (word - 1)
            }
        }
    }

    private fun layerEmpty(rev: RevLongArray, layer: Int): Boolean {
        val base = layer * w
        for (k in 0 until w) if (rev[base + k] != 0L) return false
        return true
    }

    /** Write [tmp] into [rev]'s [layer] words (reversibly); return whether any word changed. */
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

    /** fwd[i+1] = { δ(q,s) : q ∈ fwd[i], s ∈ dom(seq[i]) }. Returns whether fwd[i+1] changed. */
    private fun recomputeForward(state: PropagationState, i: Int, scratch: LongArray): Boolean {
        scratch.fill(0L)
        val d = state.intDomains[seq[i]]
        forEachState(fwd, i) { q ->
            d.values.forEach { s ->
                val nx = delta(q, s)
                if (nx != 0) scratch[(nx - 1) ushr 6] = scratch[(nx - 1) ushr 6] or (1L shl ((nx - 1) and 63))
            }
        }
        return writeLayer(fwd, i + 1, scratch)
    }

    /** bwd[i] = { q ∈ fwd[i] : ∃ s ∈ dom(seq[i]), δ(q,s) ∈ bwd[i+1] }. Returns whether bwd[i] changed. */
    private fun recomputeBackward(state: PropagationState, i: Int, scratch: LongArray): Boolean {
        scratch.fill(0L)
        val d = state.intDomains[seq[i]]
        forEachState(fwd, i) { q ->
            var alive = false
            d.values.forEach { s ->
                val nx = delta(q, s)
                if (nx != 0 && testBit(bwd, i + 1, nx - 1)) alive = true
            }
            if (alive) scratch[(q - 1) ushr 6] = scratch[(q - 1) ushr 6] or (1L shl ((q - 1) and 63))
        }
        return writeLayer(bwd, i, scratch)
    }

    /** bwd[n] = accepting ∩ fwd[n]. Returns whether it changed. */
    private fun recomputeAcceptLayer(scratch: LongArray): Boolean {
        scratch.fill(0L)
        for (q in accepting) {
            if (q in 1..numStates && testBit(fwd, n, q - 1)) {
                scratch[(q - 1) ushr 6] = scratch[(q - 1) ushr 6] or (1L shl ((q - 1) and 63))
            }
        }
        return writeLayer(bwd, n, scratch)
    }

    /** Prune every position in `[lo, hi]`: a symbol survives iff some forward-reachable state at i
     *  transitions on it into a backward-co-reachable state at i+1. Returns false on a wipeout. */
    private fun prune(state: PropagationState, lo: Int, hi: Int): Boolean {
        val ant = state.composeIntVarAtomAntecedents(seq)
        for (i in lo..hi) {
            val d = state.intDomains[seq[i]]
            var toRemove: LongArrayList? = null
            d.values.forEach { s ->
                var live = false
                forEachState(fwd, i) { q ->
                    if (!live) {
                        val nx = delta(q, s)
                        if (nx != 0 && testBit(bwd, i + 1, nx - 1)) live = true
                    }
                }
                if (!live) (toRemove ?: LongArrayList().also { toRemove = it }).add(s)
            }
            toRemove?.let { if (!state.excludeIntValues(seq[i], it.toLongArray(), ant)) return false }
        }
        return true
    }

    fun propagate(state: PropagationState, factorId: Int): Boolean {
        conflictPrefix = null
        val dirty = state.drainIntEventDirtyVars(factorId)
        if (valid.value == 1 && dirty.isEmpty()) return true
        return if (valid.value == 0) rebuild(state) else delta(state, dirty)
    }

    /** Full forward+backward build (mirrors the original one-shot propagator), then prune all. */
    private fun rebuild(state: PropagationState): Boolean {
        val scratch = LongArray(w)
        for (k in 0 until (n + 1) * w) {
            fwd[k] = 0L
            bwd[k] = 0L
        }
        scratch.fill(0L)
        scratch[(q0 - 1) ushr 6] = 1L shl ((q0 - 1) and 63)
        writeLayer(fwd, 0, scratch)
        for (i in 0 until n) {
            if (layerEmpty(fwd, i)) {
                conflictPrefix = seq.copyOfRange(0, i)
                return false
            }
            recomputeForward(state, i, scratch)
        }
        if (layerEmpty(fwd, n)) {
            conflictPrefix = seq.copyOf()
            return false
        }
        recomputeAcceptLayer(scratch)
        if (layerEmpty(bwd, n)) return false
        for (i in n - 1 downTo 0) recomputeBackward(state, i, scratch)
        if (!testBit(bwd, 0, q0 - 1)) return false
        valid.set(1)
        return prune(state, 0, n - 1)
    }

    /** Delta fire: recompute only the layers reachable from the changed positions. */
    private fun delta(state: PropagationState, dirty: IntArray): Boolean {
        // Map dirty var ids to seq positions (a var may occupy several positions).
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
        if (maxD < 0) return true // delta touched no seq var

        val scratch = LongArray(w)
        // Forward sweep up from the lowest dirty position; stop once past the dirty range with no change.
        var lastFwdChanged = minD
        var i = minD
        while (i < n) {
            if (layerEmpty(fwd, i)) {
                conflictPrefix = seq.copyOfRange(0, i)
                return false
            }
            val ch = recomputeForward(state, i, scratch)
            if (ch) lastFwdChanged = i + 1
            if (i >= maxD && !ch) break
            i++
        }
        if (layerEmpty(fwd, lastFwdChanged)) {
            conflictPrefix = seq.copyOfRange(0, minOf(lastFwdChanged, n))
            return false
        }

        // Backward sweep down from the deepest changed layer (forward changes shrink co-reachability).
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
        if (!testBit(bwd, 0, q0 - 1)) return false

        // Re-prune the affected position window (fwd[i] or bwd[i+1] may have moved). A superset is
        // sound — an unchanged position simply finds nothing to remove.
        val lo = maxOf(0, minOf(minD, firstBwdChanged - 1))
        val hi = minOf(n - 1, maxOf(maxD, lastFwdChanged))
        return prune(state, lo, hi)
    }
}
