package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList

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

    /** STR2 sparse-set state. [validTuples] holds tuple indices; the prefix
     *  `[0, numValid)` is live (still feasible). On push the engine clones via
     *  [snapshotCopy]; on pop the cloned state is restored, so [numValid] correctly
     *  reflects the level we backjumped to. */
    private class Str2State(
        val validTuples: IntArray,
        var numValid: Int,
    ) : PropagationState.SnapshottablePayload {
        override fun snapshotCopy(): Str2State = Str2State(validTuples.copyOf(), numValid)
    }

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
     * STR2 (Lecoutre 2011). The propagator maintains a sparse set of currently-feasible
     * tuple indices in [Str2State] across propagator calls; on each fire it sweeps only
     * the live prefix to drop newly-infeasible tuples and gather column supports.
     * Backtrack correctness comes from [PropagationState.SnapshottablePayload]: push
     * clones the state, pop restores it.
     *
     * Per-prune antecedents and the conflict reason are hole-aware via
     * [collectHoleAndBoundAntecedents].
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val s = (state.refPayload[factorId] as? Str2State) ?: run {
            val fresh = Str2State(IntArray(numTuples) { it }, numTuples)
            state.refPayload[factorId] = fresh
            fresh
        }
        val supports = Array(arity) { HashSet<Int>() }
        var i = 0
        while (i < s.numValid) {
            val row = s.validTuples[i]
            var feasible = true
            for (col in 0 until arity) {
                val v = tuples[row * arity + col]
                if (v !in state.intDomains[xs[col]]) { feasible = false; break }
            }
            if (!feasible) {
                val last = s.numValid - 1
                if (i != last) {
                    s.validTuples[i] = s.validTuples[last]
                    s.validTuples[last] = row
                }
                s.numValid = last
                // Don't advance i — the swapped-in tuple at i hasn't been checked.
            } else {
                for (col in 0 until arity) supports[col].add(tuples[row * arity + col])
                i++
            }
        }
        if (s.numValid == 0) return false
        val ant = collectHoleAndBoundAntecedents(state, xs)
        for (col in 0 until arity) {
            val sup = supports[col]
            val minSup = sup.min(); val maxSup = sup.max()
            if (!state.tightenIntMin(xs[col], minSup, ant)) return false
            if (!state.tightenIntMax(xs[col], maxSup, ant)) return false
            val d = state.intDomains[xs[col]]
            val toRemove = IntArrayList()
            d.forEach { value -> if (value !in sup) toRemove.add(value) }
            for (k in 0 until toRemove.size) if (!state.excludeIntValue(xs[col], toRemove[k], ant)) return false
        }
        return true
    }
}
