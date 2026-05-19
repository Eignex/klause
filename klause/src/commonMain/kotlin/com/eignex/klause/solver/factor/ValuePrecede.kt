package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `value_precede(s, t, xs)` — if value `t` appears in [xs], then the first occurrence of `s`
 * precedes the first occurrence of `t`. Standard symmetry-breaking constraint on
 * permutation / colour-assignment classes.
 *
 * `value_precede_chain(values, xs)` is built as a sequence of `ValuePrecede(values[i],
 * values[i+1], xs)` factors at the FZN-dispatch level — one factor per consecutive pair
 * — so chain semantics fall out for free.
 *
 * Propagation in this first cut is a singleton-violation check at all-pinned time.
 * Per-prefix value-locking (e.g. "if `xs[0] = t`, then UNSAT") lands with full strength
 * later — the current bound-only propagation lets BacktrackSolver find correct models
 * because the singleton check fires at every leaf attempt.
 */
class ValuePrecede(
    val s: Int,
    val t: Int,
    val xs: IntArray,
) : LocalSearchFactor {

    init {
        require(xs.isNotEmpty()) { "value_precede: empty xs" }
        require(s != t) { "value_precede: s and t must differ" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    override fun initialize(state: LocalSearchState, factorId: Int) {
        // No payload — relation recomputed each query.
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = !satisfied(state)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = !satisfied(state)
        val willViolate = !satisfiedWithOverride(state, intVar, newValue)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    private fun satisfied(state: LocalSearchState): Boolean = walk { state.assignment.intValue(it) }

    private fun satisfiedWithOverride(
        state: LocalSearchState, intVar: Int, override: Int,
    ): Boolean = walk { x -> if (x == intVar) override else state.assignment.intValue(x) }

    private inline fun walk(getValue: (Int) -> Int): Boolean {
        for (x in xs) {
            val v = getValue(x)
            // First `t` before first `s` → violated.
            if (v == t) return false
            if (v == s) return true   // first `s` encountered → constraint satisfied
        }
        // Neither `s` nor `t` ever appeared → vacuously true.
        return true
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // All-singleton sanity check: walk and detect first-t before first-s.
        var allSingleton = true
        for (x in xs) {
            val d = state.intDomains[x]
            if (d.min != d.max) { allSingleton = false; break }
        }
        if (allSingleton) {
            for (x in xs) {
                val v = state.intDomains[x].min
                if (v == t) return false  // violated: first t before first s
                if (v == s) return true   // satisfied
            }
        }
        return true
    }
}
