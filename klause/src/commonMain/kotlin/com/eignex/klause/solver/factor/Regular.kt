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
     * Singleton-only forward run: when every `seq[i]` is a singleton, simulate and fail
     * on rejection / non-accepting terminal state. Range-based per-position pruning awaits
     * the layered-DAG implementation.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
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
