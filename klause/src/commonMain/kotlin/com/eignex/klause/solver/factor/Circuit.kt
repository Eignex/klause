package com.eignex.klause.solver.factor

import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Hamiltonian-cycle constraint: `succ` is an array of `n` variables, each holding the index
 * of the next node in the circuit. A valid assignment forms exactly one cycle that visits
 * every node — starting from any node and following `succ` repeatedly returns to the start
 * after exactly `n` steps with all `n` nodes visited.
 *
 * Semantics:
 *  - `succ[i] = j` reads "node `j` is the successor of node `i`".
 *  - Domain: each `succ[i]` must hold a value in `[0, n)`. Out-of-range values count as
 *    violations.
 *  - Self-loops (`succ[i] = i`) are violations when `n ≥ 2` — a self-loop excludes node `i`
 *    from any cycle of length ≥ 2. Use [Subcircuit] when self-loops should be allowed as
 *    "node excluded from the circuit".
 *  - Sub-cycles (e.g. `succ[0]=1, succ[1]=0` with `n ≥ 3`) are violations.
 *
 * Propagation is intentionally weak — currently just bounds and self-loop avoidance — so the
 * full Hamiltonian property is enforced at LS scoring time via [isViolated]. A future
 * dedicated propagator (no-sub-cycle reasoning via union-find of fixed segments) is on the
 * TODO; see `[CP] Factor: circuit` in README.
 */
class Circuit(val succ: IntArray) : LocalSearchFactor {

    init { require(succ.size >= 1) { "Circuit needs at least one var, got ${succ.size}" } }

    override val boolVars: IntArray = EMPTY
    override val intVars: IntArray = succ

    private val n: Int = succ.size
    /** Reverse map var-id → position in [succ], for delta-from-IntVar paths. Var ids are
     *  dense in klause but [succ] may carry them in arbitrary order; a pre-computed index
     *  turns "find the position of this var" into an O(1) lookup. */
    private val positionOfVar: Map<Int, Int> = succ.withIndex().associate { (i, v) -> v to i }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        // No payload — isViolated recomputes from the assignment every call.
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        !formsCompleteCycle(state, replaceAt = -1, replaceWith = 0)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val pos = positionOfVar[intVar] ?: return 0
        val wasViolated = !formsCompleteCycle(state, replaceAt = -1, replaceWith = 0)
        val willViolate = !formsCompleteCycle(state, replaceAt = pos, replaceWith = newValue)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val pos = positionOfVar[intVar] ?: return 0
        // After-apply: assignment already updated. We need before-vs-after delta.
        val nowViolated = !formsCompleteCycle(state, replaceAt = -1, replaceWith = 0)
        // Reconstruct pre-state by swapping pos's value back to oldValue.
        val wasViolated = !formsCompleteCycle(state, replaceAt = pos, replaceWith = oldValue)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /**
     * Walk the successor graph from node 0; return true iff the assignment (with the
     * optional `succ[replaceAt] = replaceWith` override) is a single cycle of length `n`
     * visiting every node exactly once. Returns false on out-of-domain values, self-loops
     * (`n ≥ 2`), and sub-cycles. O(n) per call.
     */
    private fun formsCompleteCycle(state: LocalSearchState, replaceAt: Int, replaceWith: Int): Boolean {
        if (n == 1) {
            val v = if (replaceAt == 0) replaceWith else state.assignment.intValue(succ[0])
            return v == 0
        }
        val visited = BooleanArray(n)
        var node = 0
        for (step in 0 until n) {
            if (visited[node]) return false // returned to an already-visited node (sub-cycle)
            visited[node] = true
            val nextVal = if (node == replaceAt) replaceWith else state.assignment.intValue(succ[node])
            if (nextVal < 0 || nextVal >= n) return false
            if (nextVal == node) return false // self-loop forbidden for n ≥ 2
            node = nextVal
        }
        return node == 0 // must close the cycle
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Bounds: every succ[i]'s domain must intersect [0, n).
        // Self-loops: when n ≥ 2, succ[i] != i — tighten by shaving the value-i endpoint.
        // Full sub-cycle avoidance is left to a future dedicated propagator.
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            val newLo = maxOf(d.min, 0)
            val newHi = minOf(d.max, n - 1)
            if (newLo > newHi) return false
            if (newLo != d.min && !state.tightenIntMin(v, newLo)) return false
            if (newHi != d.max && !state.tightenIntMax(v, newHi)) return false
            // Shave self-loop value `i` from endpoints; can't punch holes in the middle
            // without a richer domain representation, so this only fires when `i` is at min/max.
            if (n >= 2) {
                val dd = state.intDomains[v]
                if (dd.min == i && dd.min < dd.max) {
                    if (!state.tightenIntMin(v, dd.min + 1)) return false
                } else if (dd.max == i && dd.min < dd.max) {
                    if (!state.tightenIntMax(v, dd.max - 1)) return false
                } else if (dd.min == dd.max && dd.min == i) {
                    return false
                }
            }
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        // Generic repair: propose changing each succ var to each value in its domain that
        // breaks the current sub-cycle structure. Capped by domain size; if domains are
        // very large, defaults to ±1 nudge.
        for (i in succ.indices) {
            val v = succ[i]
            val cur = state.assignment.intValue(v)
            val d = state.problem.intDomains[v]
            // Try at most MAX_TARGETS values, sampled across the domain.
            val span = d.size
            if (span <= MAX_TARGETS) {
                for (target in d.min..d.max) {
                    if (target != cur && target != i) sink.addIntSet(v, target)
                }
            } else {
                // Large domain — propose a few sampled targets.
                if (cur < d.max) sink.addIntSet(v, cur + 1)
                if (cur > d.min) sink.addIntSet(v, cur - 1)
                // Plus a random spread (state.rng) to break out of local plateaus.
                repeat(MAX_TARGETS) {
                    val target = d.min + state.rng.nextInt(span)
                    if (target != cur && target != i) sink.addIntSet(v, target)
                }
            }
        }
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
        const val MAX_TARGETS: Int = 4
    }
}
