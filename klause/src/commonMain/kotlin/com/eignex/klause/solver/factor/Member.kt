package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `member_int(xs, y)` — `y` equals at least one of the `xs[i]`. The dual of
 * disjunction-of-equalities: `(y = xs[0]) ∨ (y = xs[1]) ∨ … ∨ (y = xs[n-1])`.
 *
 * Propagation in this first cut: when every `xs[i]`'s domain is disjoint from `y`'s
 * domain, fail; when `xs` has length 1, force `y = xs[0]`. Per-value support tightening
 * lands when full strength propagators are in scope.
 */
class Member(
    val xs: IntArray,
    val y: Int,
) : LocalSearchFactor {

    init {
        require(xs.isNotEmpty()) { "member: empty xs" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(y)

    override fun initialize(state: LocalSearchState, factorId: Int) {}

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val yv = state.assignment.intValue(y)
        for (x in xs) if (state.assignment.intValue(x) == yv) return false
        return true
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = isViolated(state, factorId)
        val yv = if (intVar == y) newValue else state.assignment.intValue(y)
        var matched = false
        for (x in xs) {
            val xv = if (x == intVar) newValue else state.assignment.intValue(x)
            if (xv == yv) { matched = true; break }
        }
        val willViolate = !matched
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Singleton-y check: if y is singleton and no xs[i]'s domain contains y's value, fail.
        val dy = state.intDomains[y]
        if (dy.min == dy.max) {
            val yv = dy.min
            var anyContains = false
            for (x in xs) if (yv in state.intDomains[x]) { anyContains = true; break }
            if (!anyContains) return false
        }
        // Singleton-xs[i]: if every xs[i] is singleton, y must equal one of them.
        var allSingleton = true
        for (x in xs) if (state.intDomains[x].min != state.intDomains[x].max) { allSingleton = false; break }
        if (allSingleton) {
            val values = HashSet<Int>()
            for (x in xs) values.add(state.intDomains[x].min)
            // Restrict y's domain to the value set.
            val toRemove = ArrayList<Int>()
            dy.forEach { if (it !in values) toRemove.add(it) }
            for (v in toRemove) if (!state.excludeIntValue(y, v)) return false
        }
        return true
    }
}
