package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `among(n, xs, S)` — `n = #{i : xs[i] ∈ S}` where [values] is a constant set. Generalises
 * [Count] for membership in a fixed set rather than a single ⟨op⟩ value.
 *
 * The value set is stored sorted (deduplicated) for O(log k) membership checks. For typical
 * MiniZinc usage `|S|` is small (a handful of "good" values among many), so the constant
 * factor matters less than the structure being explicit.
 */
class Among(
    val n: Int,
    val xs: IntArray,
    values: IntArray,
) : LocalSearchFactor {

    /** Sorted, deduplicated value set. */
    val values: IntArray = values.toSortedSet().toIntArray()

    init {
        require(xs.isNotEmpty()) { "among: empty xs" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(n)

    private class State(var count: Int)

    private fun matches(value: Int): Boolean {
        // Sorted set → binary search.
        var lo = 0
        var hi = values.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val mv = values[mid]
            when {
                mv < value -> lo = mid + 1
                mv > value -> hi = mid - 1
                else -> return true
            }
        }
        return false
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
            val wasMatch = matches(state.assignment.intValue(intVar))
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

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var definite = 0
        var possible = 0
        for (x in xs) {
            val d = state.intDomains[x]
            var allIn = true
            var anyIn = false
            d.forEach { value ->
                if (matches(value)) anyIn = true
                else allIn = false
            }
            if (allIn) definite++
            if (anyIn) possible++
        }
        val ant = state.composeIntVarAntecedents(xs)
        if (!state.tightenIntMin(n, definite, ant)) return false
        if (!state.tightenIntMax(n, possible, ant)) return false
        return true
    }
}
