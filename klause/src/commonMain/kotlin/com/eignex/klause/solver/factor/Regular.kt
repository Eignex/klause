package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList

/**
 * `regular(seq, Q, S, d, q0, F)` — the sequence `seq` is accepted by the DFA with
 * `Q` states, alphabet size `S`, transition function [transitions] indexed by
 * `(q-1) * S + (s-1)` (row-major, 1-based states and symbols), initial state [q0],
 * and accepting set [accepting]. A transition value of `0` denotes rejection (the
 * "dead state").
 *
 * Decomposed propagation: when every `seq[i]` is singleton, simulate the run and verify
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
) : LocalSearchFactor {

    /** Accepting states as a set for O(1) membership. */
    val acceptingSet: HashSet<Int> = accepting.toHashSet()

    init {
        require(seq.isNotEmpty()) { "regular: empty seq" }
        require(numStates >= 1) { "regular: numStates ≥ 1" }
        require(alphabetSize >= 1) { "regular: alphabetSize ≥ 1" }
        require(transitions.size == numStates * alphabetSize) {
            "regular: transitions must be Q*S = ${numStates * alphabetSize} entries, got ${transitions.size}"
        }
        require(q0 in 1..numStates) { "regular: q0 ($q0) out of [1, $numStates]" }
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

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = !accepts(state)
        val willViolate = !acceptsWithOverride(state, intVar, newValue)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Repair by replaying the DFA up to the first dead position; at that position propose
     *  every in-domain symbol that yields a valid transition from the live state, plus
     *  symbols that reach the accepting set when the run completed without accepting. */
    override fun proposeRepairMoves(
        state: LocalSearchState,
        factorId: Int,
        sink: com.eignex.klause.solver.localsearch.MoveSink,
    ) {
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

    private fun accepts(state: LocalSearchState): Boolean = run(false, state, 0, 0)
    private fun acceptsWithOverride(state: LocalSearchState, intVar: Int, value: Int): Boolean =
        run(true, state, intVar, value)

    private fun run(useOverride: Boolean, state: LocalSearchState, intVar: Int, value: Int): Boolean {
        var q = q0
        for (i in seq.indices) {
            val s = if (useOverride && seq[i] == intVar) value else state.assignment.intValue(seq[i])
            q = delta(q, s)
            if (q == 0) return false
        }
        return q in acceptingSet
    }

    /** Number of 64-bit words needed to bitmask `numStates`. */
    private val stateWords: Int = (numStates + 63) ushr 6

    /**
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

    /** Hole-aware conflict reason. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, seq)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = seq.size
        val w = stateWords
        val forward = LongArray((n + 1) * w)
        // Layer 0: only q0 is reachable.
        setBit(forward, 0, q0 - 1)
        for (i in 0 until n) {
            if (isLayerEmpty(forward, i)) return false
            val d = state.intDomains[seq[i]]
            d.forEach { s ->
                forEachStateInLayer(forward, i) { q ->
                    val nx = delta(q, s)
                    if (nx != 0) setBit(forward, i + 1, nx - 1)
                }
            }
        }
        val backward = LongArray((n + 1) * w)
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

    private inline fun setBit(bits: LongArray, layer: Int, bit: Int) {
        bits[layer * stateWords + (bit ushr 6)] = bits[layer * stateWords + (bit ushr 6)] or (1L shl (bit and 63))
    }

    private inline fun testBit(bits: LongArray, layer: Int, bit: Int): Boolean =
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
