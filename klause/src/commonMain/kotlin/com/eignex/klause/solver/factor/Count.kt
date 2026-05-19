package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `count_⟨op⟩(xs, v, n)` — `n = #{i : xs[i] ⟨op⟩ v}`. Single factor covering all six
 * MiniZinc count variants via [op]:
 *
 *  - [Op.Eq]:  `xs[i] = v`
 *  - [Op.Ne]:  `xs[i] ≠ v`
 *  - [Op.Le]:  `xs[i] ≤ v`
 *  - [Op.Lt]:  `xs[i] < v`
 *  - [Op.Ge]:  `xs[i] ≥ v`
 *  - [Op.Gt]:  `xs[i] > v`
 *
 * Variants where `v` is a variable rather than a constant land via the existing decomposition
 * path (channel `xs[i] − v ⟨op⟩ 0` through reified linears); this factor takes a *constant*
 * target — the common case in MiniZinc-emitted FlatZinc.
 */
class Count(
    val xs: IntArray,
    val v: Int,
    val op: Op,
    val n: Int,
) : LocalSearchFactor {

    enum class Op { Eq, Ne, Le, Lt, Ge, Gt }

    init {
        require(xs.isNotEmpty()) { "count: empty xs" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(n)

    /** Cached count of xs[i] satisfying the predicate under the current assignment. */
    private class State(var count: Int)

    private fun matches(value: Int): Boolean = when (op) {
        Op.Eq -> value == v
        Op.Ne -> value != v
        Op.Le -> value <= v
        Op.Lt -> value < v
        Op.Ge -> value >= v
        Op.Gt -> value > v
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var c = 0
        for (x in xs) if (matches(state.assignment.intValue(x))) c++
        state.refPayload[factorId] = State(c)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return state.assignment.intValue(n) != s.count
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = state.assignment.intValue(n) != s.count
        var deltaCount = 0
        for (x in xs) {
            if (x != intVar) continue
            val old = state.assignment.intValue(intVar)
            val wasMatch = matches(old)
            val willMatch = matches(newValue)
            if (wasMatch && !willMatch) deltaCount--
            if (!wasMatch && willMatch) deltaCount++
        }
        val newCount = s.count + deltaCount
        val newN = if (intVar == n) newValue else state.assignment.intValue(n)
        val willViolate = newN != newCount
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = state.assignment.intValue(n) != s.count
        for (x in xs) {
            if (x != intVar) continue
            val wasMatch = matches(oldValue)
            val nowMatch = matches(cur)
            if (wasMatch && !nowMatch) s.count--
            if (!wasMatch && nowMatch) s.count++
        }
        val nowViolated = state.assignment.intValue(n) != s.count
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /**
     * `n` bounded by the count of definite-matchers (lower) and possible-matchers (upper).
     * A var is a definite-matcher when its *entire* domain satisfies the predicate; a
     * possible-matcher when *some* of its domain does.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var definite = 0
        var possible = 0
        for (x in xs) {
            val d = state.intDomains[x]
            val all = domainAllMatches(d)
            val any = domainAnyMatches(d)
            if (all) definite++
            if (any) possible++
        }
        val ant = state.composeIntVarAtomAntecedents(xs)
        if (!state.tightenIntMin(n, definite, ant)) return false
        if (!state.tightenIntMax(n, possible, ant)) return false
        return true
    }

    private fun domainAllMatches(d: com.eignex.klause.solver.IntDomain): Boolean = when (op) {
        Op.Eq -> d.min == d.max && d.min == v
        Op.Ne -> d.max < v || d.min > v
        Op.Le -> d.max <= v
        Op.Lt -> d.max < v
        Op.Ge -> d.min >= v
        Op.Gt -> d.min > v
    }

    private fun domainAnyMatches(d: com.eignex.klause.solver.IntDomain): Boolean = when (op) {
        Op.Eq -> v in d
        Op.Ne -> !(d.min == d.max && d.min == v)
        Op.Le -> d.min <= v
        Op.Lt -> d.min < v
        Op.Ge -> d.max >= v
        Op.Gt -> d.max > v
    }
}
