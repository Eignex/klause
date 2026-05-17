package com.eignex.klause.solver.factor

import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Hamiltonian-cycle constraint with optional exclusions. Like [Circuit], but `succ[i] = i`
 * (a self-loop) is permitted and reads "node `i` is not in the cycle". The remaining nodes —
 * those with `succ[i] != i` — must form exactly one cycle visiting every included node.
 *
 * Semantics:
 *  - `succ[i] = j ≠ i` reads "j is the successor of i in the cycle".
 *  - `succ[i] = i` reads "i is excluded".
 *  - The included set (where `succ[i] != i`) must form a single closed cycle: follow `succ`
 *    from any included node, return after visiting every included node exactly once.
 *  - Out-of-range values, sub-cycles among the included nodes, and "successor pointing to
 *    an excluded node" are all violations.
 *  - Degenerate cases: all nodes excluded (every `succ[i] = i`) — the empty cycle —
 *    is valid. Exactly one included node is also valid only if that node self-loops, but
 *    self-loop = excluded, so "exactly one included" can only arise when that one node's
 *    successor is itself (= excluded), so it's not really included. Net: either 0 included
 *    or ≥ 2 included.
 *
 * Same weak-propagation note as [Circuit]: bounds only; the full Hamiltonian property is
 * enforced at LS scoring.
 */
class Subcircuit(val succ: IntArray) : LocalSearchFactor {

    init { require(succ.size >= 1) { "Subcircuit needs at least one var, got ${succ.size}" } }

    override val boolVars: IntArray = EMPTY
    override val intVars: IntArray = succ

    private val n: Int = succ.size
    private val positionOfVar: Map<Int, Int> = succ.withIndex().associate { (i, v) -> v to i }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        // No payload — isViolated recomputes from the assignment every call.
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        !formsValidSubcircuit(state, replaceAt = -1, replaceWith = 0)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val pos = positionOfVar[intVar] ?: return 0
        val wasViolated = !formsValidSubcircuit(state, replaceAt = -1, replaceWith = 0)
        val willViolate = !formsValidSubcircuit(state, replaceAt = pos, replaceWith = newValue)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val pos = positionOfVar[intVar] ?: return 0
        val nowViolated = !formsValidSubcircuit(state, replaceAt = -1, replaceWith = 0)
        val wasViolated = !formsValidSubcircuit(state, replaceAt = pos, replaceWith = oldValue)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /**
     * Walk the successor graph; return true iff the included nodes (those whose `succ[i] != i`)
     * form a single closed cycle visiting every included node exactly once. Allows the
     * all-excluded case as the empty cycle.
     */
    private fun formsValidSubcircuit(state: LocalSearchState, replaceAt: Int, replaceWith: Int): Boolean {
        // Read the effective successor of each node (applying the optional override).
        val succVal = IntArray(n) { i ->
            if (i == replaceAt) replaceWith else state.assignment.intValue(succ[i])
        }
        // Bounds + "successor of an included node must itself be included" check.
        for (i in 0 until n) {
            val s = succVal[i]
            if (s < 0 || s >= n) return false
            if (s != i && succVal[s] == s) return false // points to an excluded node
        }
        // Find a starting included node, walk until we return. Count included nodes seen.
        var start = -1
        var includedCount = 0
        for (i in 0 until n) {
            if (succVal[i] != i) {
                includedCount++
                if (start == -1) start = i
            }
        }
        if (includedCount == 0) return true // empty subcircuit
        if (includedCount == 1) return false // a single included node can't form a cycle without self-loop
        // Walk from start; should return after exactly `includedCount` hops.
        val visited = BooleanArray(n)
        var node = start
        for (step in 0 until includedCount) {
            if (visited[node]) return false // revisit before completing → sub-cycle
            visited[node] = true
            node = succVal[node]
        }
        return node == start
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Bounds: every succ[i]'s domain must intersect [0, n). Self-loops are valid in
        // Subcircuit, so we don't shave value `i` like Circuit does.
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            val newLo = maxOf(d.min, 0)
            val newHi = minOf(d.max, n - 1)
            if (newLo > newHi) return false
            if (newLo != d.min && !state.tightenIntMin(v, newLo)) return false
            if (newHi != d.max && !state.tightenIntMax(v, newHi)) return false
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        // Same generic repair as Circuit, plus the option to exclude (succ[i] = i) which
        // can be useful when the current configuration has a sub-cycle that's easier to
        // dissolve by dropping a node from the circuit.
        for (i in succ.indices) {
            val v = succ[i]
            val cur = state.assignment.intValue(v)
            val d = state.problem.intDomains[v]
            // Propose self-loop (exclude node) if valid and not current.
            if (i != cur && i in d.min..d.max) sink.addIntSet(v, i)
            val span = d.size
            if (span <= MAX_TARGETS) {
                for (target in d.min..d.max) {
                    if (target != cur) sink.addIntSet(v, target)
                }
            } else {
                if (cur < d.max) sink.addIntSet(v, cur + 1)
                if (cur > d.min) sink.addIntSet(v, cur - 1)
                repeat(MAX_TARGETS) {
                    val target = d.min + state.rng.nextInt(span)
                    if (target != cur) sink.addIntSet(v, target)
                }
            }
        }
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
        const val MAX_TARGETS: Int = 4
    }
}
