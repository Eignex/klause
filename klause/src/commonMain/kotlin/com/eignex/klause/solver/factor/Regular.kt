package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * `regular(seq, Q, S, d, q0, F)` — the sequence `seq` is accepted by the DFA with
 * `Q` states, alphabet size `S`, transition function [transitions] indexed by
 * `(q-1) * S + (s-1)` (row-major, 1-based states and symbols), initial state [q0],
 * and accepting set [accepting]. A transition value of `0` denotes rejection (the
 * "dead state").
 *
 * Decomposed propagation: when every `seq(i)` is singleton, simulate the run and verify
 * acceptance.
 */
class Regular(
    /** Input symbol sequence variable ids. */
    val seq: IntArray,
    /** Number of DFA states. */
    val numStates: Int,
    /** Number of input symbols. */
    val alphabetSize: Int,
    /** `numStates × alphabetSize` row-major transition table; 0 means no transition. */
    val transitions: IntArray,
    /** Initial state. */
    val q0: Int,
    /** Accepting states. */
    val accepting: IntArray,
) : Factor {

    /** Accepting states as a primitive set for O(1) boxing-free membership in the hot
     *  acceptance checks (`q in acceptingSet`). */
    internal val acceptingSet: IntHashSet = run {
        val s = IntHashSet(accepting.size)
        for (q in accepting) s.add(q)
        s
    }

    init {
        require(seq.isNotEmpty()) { "regular: empty seq" }
        require(numStates >= 1) { "regular: numStates ≥ 1" }
        require(alphabetSize >= 1) { "regular: alphabetSize ≥ 1" }
        require(transitions.size == numStates * alphabetSize) {
            "regular: transitions must be Q*S = ${numStates * alphabetSize} entries, got ${transitions.size}"
        }
        require(q0 in 1..numStates) { "regular: q0 ($q0) out of [1, $numStates]" }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        Regular(seq.remapVars(intMap), numStates, alphabetSize, transitions, q0, accepting)

    /** Position-faithful (seq position i matters): keeps the sequence vars in order and folds in the
     *  whole automaton — state/alphabet sizes, the transition table, the initial and accepting states
     *  (#531). */
    override fun structuralKey(): String = "regular:$numStates:$alphabetSize:$q0:${transitions.joinToString(",")}:" +
        "${accepting.joinToString(",")}:${seq.joinToString(",")}"

    /** Symbol-alphabet relabeling (#536): the `seq` values *are* the symbols, so a value permutation
     *  permutes the transition table's symbol axis — `δ'(q, valueMap(s)) = δ(q, s)`. Sound because
     *  Regular has no positional-variable/constant coupling (unlike Element): swapping symbol values in
     *  a sequence and the matching columns preserves acceptance exactly. Returns `null` if [valueMap]
     *  is not a permutation of `1..alphabetSize` (then it can't relabel this automaton's columns). */
    override fun remapValues(valueMap: (Int) -> Int): Factor? {
        val target = IntArray(alphabetSize + 1) // 1-based symbols
        val seen = BooleanArray(alphabetSize + 1)
        for (s in 1..alphabetSize) {
            val t = valueMap(s)
            if (t < 1 || t > alphabetSize || seen[t]) return null
            seen[t] = true
            target[s] = t
        }
        val newTransitions = IntArray(transitions.size)
        for (q in 1..numStates) {
            for (s in 1..alphabetSize) {
                newTransitions[(q - 1) * alphabetSize + (target[s] - 1)] = transitions[(q - 1) * alphabetSize + (s - 1)]
            }
        }
        return Regular(seq, numStates, alphabetSize, newTransitions, q0, accepting)
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = seq

    /** Look up `δ(state, symbol)` with 1-based addressing. Returns 0 for the dead state. */
    private fun delta(state: Int, symbol: Int): Int {
        if (state < 1 || state > numStates) return 0
        if (symbol < 1 || symbol > alphabetSize) return 0
        return transitions[(state - 1) * alphabetSize + (symbol - 1)]
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = !accepts(state)

    /** Graded violation: the minimum number of sequence positions whose symbol must change for
     *  the DFA to accept — an edit-distance-to-language computed by a forward DP over states —
     *  compressed. `0` iff the current string is accepted; a string whose length admits no
     *  accepted word saturates at `seq.size + 1`. Gives CBLS a gradient toward acceptance
     *  instead of a flat boolean. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation(acceptDistance { state.assignment.intValue(seq[it]) }.toLong(), state.violationSoftCap)

    /** Min symbol changes to reach an accepting run, where `getSym(i)` is position `i`'s current
     *  symbol (a transition on it costs 0, any other symbol costs 1). */
    private inline fun acceptDistance(getSym: (Int) -> Int): Int {
        val inf = seq.size + 1
        var dp = IntArray(numStates + 1) { inf }
        dp[q0] = 0
        for (i in seq.indices) {
            val cur = getSym(i)
            val ndp = IntArray(numStates + 1) { inf }
            for (q in 1..numStates) {
                val base = dp[q]
                if (base >= inf) continue
                for (sym in 1..alphabetSize) {
                    val nq = delta(q, sym)
                    if (nq == 0) continue
                    val cost = base + (if (sym == cur) 0 else 1)
                    if (cost < ndp[nq]) ndp[nq] = cost
                }
            }
            dp = ndp
        }
        var best = inf
        for (q in 1..numStates) if (q in acceptingSet && dp[q] < best) best = dp[q]
        return best
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val before = acceptDistance { state.assignment.intValue(seq[it]) }
        val after = acceptDistance {
            val v = seq[it]
            if (v == intVar) newValue else state.assignment.intValue(v)
        }
        return compressViolation(after.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Repair by replaying the DFA up to the first dead position; at that position propose
     *  every in-domain symbol that yields a valid transition from the live state, plus
     *  symbols that reach the accepting set when the run completed without accepting. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        var q = q0
        for (i in seq.indices) {
            val s = state.assignment.intValue(seq[i])
            val next = delta(q, s)
            if (next == 0) {
                // First dead position: propose every alphabet symbol in domain that keeps q alive.
                val d = state.problem.intDomains[seq[i]]
                d.forEach { sym ->
                    if (sym != s && delta(q, sym) != 0) sink.addChannelingIntSet(state, seq[i], sym)
                }
                return
            }
            q = next
        }
        // Run completed but final state not accepting. Try last-position symbol changes that
        // would land in an accepting state.
        if (q !in acceptingSet && seq.isNotEmpty()) {
            val last = seq.size - 1
            // Recompute state at last-1.
            var qPrev = q0
            for (i in 0 until last) qPrev = delta(qPrev, state.assignment.intValue(seq[i]))
            val curLast = state.assignment.intValue(seq[last])
            val d = state.problem.intDomains[seq[last]]
            d.forEach { sym ->
                val target = delta(qPrev, sym)
                if (sym != curLast && target in acceptingSet) sink.addChannelingIntSet(state, seq[last], sym)
            }
        }
    }

    private fun accepts(state: LocalSearchState): Boolean {
        var q = q0
        for (i in seq.indices) {
            q = delta(q, state.assignment.intValue(seq[i]))
            if (q == 0) return false
        }
        return q in acceptingSet
    }

    /** Number of 64-bit words needed to bitmask `numStates`. */
    private val stateWords: Int = (numStates + 63) ushr 6

    /*
     * Pesant's layered-DAG GAC propagator, bitmask-encoded. Build the unrolled DFA
     * across `n = seq.size` layers; per layer, a `stateWords`-long bitmask records
     * which states are forward-reachable (resp. co-reachable from accepting).
     *
     * Pruning: at each layer, a symbol `s ∈ dom(seq[i])` survives iff `∃ q` forward-
     * reachable at `i` whose transition `δ(q, s)` is co-reachable at `i+1`. Non-
     * surviving symbols are removed from `dom(seq[i])`. Conflict iff the initial
     * state is not co-reachable at layer 0.
     *
     * Memory: two `LongArray((n+1) * stateWords)` per call — `O(n · Q/64)` longs.
     * For Q ≤ 64 this collapses to two `LongArray(n+1)`; for larger Q the multi-
     * Long encoding still avoids per-layer object allocation.
     */

    /** Hole-aware conflict reason, sharpened to the responsible prefix when [propagate]
     *  captured a forward-collapse layer; falls back to the whole sequence otherwise. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, (state.refPayload[factorId] as? Scratch)?.conflictPrefix ?: seq)

    /**
     * Per-[PropagationState] reusable propagation scratch (so it is never shared across concurrent
     * worker threads). [forward] / [backward] are the layer-bitset working buffers — sized once to
     * `(seq.size + 1) * stateWords` and refilled from zero on every fire, so reusing them across
     * calls drops the two per-call `LongArray` allocations. [conflictPrefix] records the responsible
     * `seq` prefix when a forward layer collapses, for [conflictReason]. Not a
     * [PropagationState.SnapshottablePayload]: the buffers are recomputed every fire and the prefix
     * is advisory, so the slot intentionally drifts across snapshot / restore (like CDCL watches).
     */
    private class Scratch(size: Int) {
        val forward = LongArray(size)
        val backward = LongArray(size)
        var conflictPrefix: IntArray? = null
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = seq.size
        val w = stateWords
        val scratch = (state.refPayload[factorId] as? Scratch) ?: run {
            val fresh = Scratch((n + 1) * w)
            state.refPayload[factorId] = fresh
            fresh
        }
        scratch.conflictPrefix = null // stale-guard; set at the forward-collapse failure point below.
        val forward = scratch.forward
        forward.fill(0L)
        // Layer 0: only q0 is reachable.
        setBit(forward, 0, q0 - 1)
        for (i in 0 until n) {
            // forward[i] empty ⇒ the prefix seq[0 until i] alone drove every state dead.
            if (isLayerEmpty(forward, i)) {
                scratch.conflictPrefix = seq.copyOfRange(0, i)
                return false
            }
            val d = state.intDomains[seq[i]]
            d.forEach { s ->
                forEachStateInLayer(forward, i) { q ->
                    val nx = delta(q, s)
                    if (nx != 0) setBit(forward, i + 1, nx - 1)
                }
            }
        }
        val backward = scratch.backward
        backward.fill(0L)
        for (q in accepting) if (q in 1..numStates) setBit(backward, n, q - 1)
        // Intersect with forward[n] so we only keep states actually reachable.
        for (k in 0 until w) {
            backward[n * w + k] = backward[n * w + k] and forward[n * w + k]
        }
        if (isLayerEmpty(backward, n)) return false
        for (i in n - 1 downTo 0) {
            val d = state.intDomains[seq[i]]
            forEachStateInLayer(forward, i) { q ->
                var alive = false
                d.forEach { s ->
                    val nx = delta(q, s)
                    if (nx != 0 && testBit(backward, i + 1, nx - 1)) alive = true
                }
                if (alive) setBit(backward, i, q - 1)
            }
        }
        if (!testBit(backward, 0, q0 - 1)) return false
        // Per-position symbol pruning.
        val ant = state.composeIntVarAtomAntecedents(seq)
        for (i in 0 until n) {
            val d = state.intDomains[seq[i]]
            val toRemove = IntArrayList()
            d.forEach { s ->
                var live = false
                forEachStateInLayer(forward, i) { q ->
                    if (!live) {
                        val nx = delta(q, s)
                        if (nx != 0 && testBit(backward, i + 1, nx - 1)) live = true
                    }
                }
                if (!live) toRemove.add(s)
            }
            for (k in 0 until toRemove.size) if (!state.excludeIntValue(seq[i], toRemove[k], ant)) return false
        }
        return true
    }

    private fun setBit(bits: LongArray, layer: Int, bit: Int) {
        bits[layer * stateWords + (bit ushr 6)] = bits[layer * stateWords + (bit ushr 6)] or (1L shl (bit and 63))
    }

    private fun testBit(bits: LongArray, layer: Int, bit: Int): Boolean =
        (bits[layer * stateWords + (bit ushr 6)] and (1L shl (bit and 63))) != 0L

    private fun isLayerEmpty(bits: LongArray, layer: Int): Boolean {
        val base = layer * stateWords
        for (k in 0 until stateWords) if (bits[base + k] != 0L) return false
        return true
    }

    private inline fun forEachStateInLayer(bits: LongArray, layer: Int, action: (Int) -> Unit) {
        val base = layer * stateWords
        for (k in 0 until stateWords) {
            var w = bits[base + k]
            while (w != 0L) {
                val q = (k shl 6) + w.countTrailingZeroBits() + 1
                if (q <= numStates) action(q)
                w = w and (w - 1)
            }
        }
    }
}
