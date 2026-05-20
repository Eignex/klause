package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `table_int(xs, tuples)` — the vector of `xs[i]` values must equal one of the rows of
 * [tuples]. The [tuples] array stores rows row-major: `tuples[i, j]` lives at
 * `tuples[i * arity + j]` in the flat representation, where `arity = xs.size`.
 *
 * Propagation in this first cut: tighten each `xs[j]` to the union of `tuples[*, j]`
 * values restricted to rows whose every column is still domain-feasible. Naive STR / GAC-3
 * support tables land when full propagator strength is in scope (next step).
 *
 * `table_bool` is supported via the same factor by channeling booleans to 0/1 ints upstream.
 */
class Table(
    val xs: IntArray,
    val tuples: IntArray,  // row-major; length must be a multiple of xs.size
) : LocalSearchFactor {

    val arity: Int = xs.size
    val numTuples: Int = tuples.size / arity

    init {
        require(xs.isNotEmpty()) { "table: empty xs" }
        require(tuples.size % arity == 0) { "table: tuples length must be a multiple of xs.size" }
        require(numTuples > 0) { "table: at least one tuple required" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    override fun initialize(state: LocalSearchState, factorId: Int) {}

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        for (row in 0 until numTuples) {
            var match = true
            for (col in 0 until arity) {
                if (state.assignment.intValue(xs[col]) != tuples[row * arity + col]) {
                    match = false; break
                }
            }
            if (match) return false
        }
        return true
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = isViolated(state, factorId)
        // Simulate by checking match against any row using the override.
        var matchAny = false
        for (row in 0 until numTuples) {
            var match = true
            for (col in 0 until arity) {
                val v = if (xs[col] == intVar) newValue else state.assignment.intValue(xs[col])
                if (v != tuples[row * arity + col]) { match = false; break }
            }
            if (match) { matchAny = true; break }
        }
        val willViolate = !matchAny
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Hole-aware conflict reason — cites every post-bake domain hole and bound shift
     *  across [xs], matching the per-prune antecedent set used in [propagate]. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    /**
     * Tighten each column's domain to the union of values at that column over feasible
     * rows. A row is feasible iff every column's tuple value lies in the corresponding
     * variable's current domain. If no row is feasible, fail. Per-prune antecedents use
     * the same hole-aware reason — every excluded value in any column influenced which
     * tuples remained supports.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val supports = Array(arity) { HashSet<Int>() }
        var anyFeasible = false
        for (row in 0 until numTuples) {
            var feasible = true
            for (col in 0 until arity) {
                val v = tuples[row * arity + col]
                if (v !in state.intDomains[xs[col]]) { feasible = false; break }
            }
            if (!feasible) continue
            anyFeasible = true
            for (col in 0 until arity) supports[col].add(tuples[row * arity + col])
        }
        if (!anyFeasible) return false
        val ant = collectHoleAndBoundAntecedents(state, xs)
        for (col in 0 until arity) {
            val sup = supports[col]
            val minSup = sup.min(); val maxSup = sup.max()
            if (!state.tightenIntMin(xs[col], minSup, ant)) return false
            if (!state.tightenIntMax(xs[col], maxSup, ant)) return false
            val d = state.intDomains[xs[col]]
            val toRemove = ArrayList<Int>()
            d.forEach { value -> if (value !in sup) toRemove.add(value) }
            for (v in toRemove) if (!state.excludeIntValue(xs[col], v, ant)) return false
        }
        return true
    }
}
