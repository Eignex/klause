package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `regular(seq, Q, S, d, q0, F)` — the sequence `seq` is accepted by the DFA with
 * `Q` states, alphabet size `S`, transition function [transitions] indexed by
 * `(q-1) * S + (s-1)` (row-major, 1-based states and symbols), initial state [q0],
 * and accepting set [accepting]. A transition value of `0` denotes rejection (the
 * "dead state").
 *
 * Decomposed propagation in this first cut: when every `seq[i]` is singleton, simulate
 * the run and verify acceptance. The classic Pesant layered-DAG propagator (forward
 * + backward arc-consistency) lands when full propagator strength is in scope (next
 * step).
 */
class Regular(
    val seq: IntArray,
    val numStates: Int,
    val alphabetSize: Int,
    val transitions: IntArray,
    val q0: Int,
    val accepting: IntArray,
) : LocalSearchFactor {

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

    override fun initialize(state: LocalSearchState, factorId: Int) {}

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = !accepts(state)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = !accepts(state)
        val willViolate = !acceptsWithOverride(state, intVar, newValue)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

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

    /**
     * Pesant's layered-DAG GAC propagator, bitmask-encoded for ≤64-state DFAs (covers
     * the vast majority of MZN models). Build the unrolled DFA across `n = seq.size`
     * layers; forward[i] is a Long where bit `q-1` marks state `q` forward-reachable
     * at layer `i`; backward[i] marks co-reachable states.
     *
     * Pruning: at each layer, a symbol `s ∈ dom(seq[i])` survives iff `∃ q` forward-
     * reachable at `i` whose transition `δ(q, s)` is co-reachable at `i+1`. Non-
     * surviving symbols are removed from `dom(seq[i])`. Conflict iff the initial
     * state is not co-reachable at layer 0.
     *
     * For DFAs with more than 64 states, falls back to the singleton-only check
     * pending a multi-Long bitmask extension.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (numStates > 64) return propagateSingletonOnly(state)
        val n = seq.size
        // forward[i]: bitmask of forward-reachable states at layer i (bit q-1 ⇒ state q).
        val forward = LongArray(n + 1)
        forward[0] = 1L shl (q0 - 1)
        for (i in 0 until n) {
            val src = forward[i]
            if (src == 0L) return false  // empty layer ⇒ infeasible.
            val d = state.intDomains[seq[i]]
            var dst = 0L
            d.forEach { s ->
                var bits = src
                while (bits != 0L) {
                    val q = bits.countTrailingZeroBits() + 1
                    val nx = delta(q, s)
                    if (nx != 0) dst = dst or (1L shl (nx - 1))
                    bits = bits and (bits - 1)
                }
            }
            forward[i + 1] = dst
        }
        // backward[i]: bitmask of co-reachable states at layer i.
        val backward = LongArray(n + 1)
        var acc = 0L
        for (q in accepting) if (q in 1..numStates) acc = acc or (1L shl (q - 1))
        backward[n] = forward[n] and acc
        if (backward[n] == 0L) return false
        for (i in n - 1 downTo 0) {
            val srcMask = forward[i]
            val nextMask = backward[i + 1]
            var aliveSrc = 0L
            val d = state.intDomains[seq[i]]
            var bits = srcMask
            while (bits != 0L) {
                val q = bits.countTrailingZeroBits() + 1
                var alive = false
                d.forEach { s ->
                    val nx = delta(q, s)
                    if (nx != 0 && (nextMask and (1L shl (nx - 1))) != 0L) alive = true
                }
                if (alive) aliveSrc = aliveSrc or (1L shl (q - 1))
                bits = bits and (bits - 1)
            }
            backward[i] = aliveSrc
        }
        if ((backward[0] and (1L shl (q0 - 1))) == 0L) return false
        // Per-position symbol pruning.
        val ant = state.composeIntVarAtomAntecedents(seq)
        for (i in 0 until n) {
            val srcMask = forward[i]
            val nextMask = backward[i + 1]
            val d = state.intDomains[seq[i]]
            val toRemove = ArrayList<Int>()
            d.forEach { s ->
                var live = false
                var bits = srcMask
                while (bits != 0L && !live) {
                    val q = bits.countTrailingZeroBits() + 1
                    val nx = delta(q, s)
                    if (nx != 0 && (nextMask and (1L shl (nx - 1))) != 0L) live = true
                    bits = bits and (bits - 1)
                }
                if (!live) toRemove.add(s)
            }
            for (v in toRemove) if (!state.excludeIntValue(seq[i], v, ant)) return false
        }
        return true
    }

    private fun propagateSingletonOnly(state: PropagationState): Boolean {
        var allSingleton = true
        for (v in seq) {
            val d = state.intDomains[v]
            if (d.min != d.max) { allSingleton = false; break }
        }
        if (!allSingleton) return true
        var q = q0
        for (v in seq) {
            q = delta(q, state.intDomains[v].min)
            if (q == 0) return false
        }
        return q in acceptingSet
    }
}
