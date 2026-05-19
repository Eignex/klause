package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `nvalue(n, xs)` — `n` equals the count of distinct values appearing in [xs]. Plus
 * variants:
 *
 *  - [Mode.Eq] (default): `n = |distinct(xs)|`.
 *  - [Mode.AtLeast]: `n ≤ |distinct(xs)|`.
 *  - [Mode.AtMost]:  `n ≥ |distinct(xs)|`.
 *
 * One factor with a mode flag so all three MiniZinc predicates (`fzn_nvalue`,
 * `fzn_atleast_nvalues`, `fzn_atmost_nvalues`) lower to the same factor type.
 */
class NValue(
    val n: Int,
    val xs: IntArray,
    val mode: Mode = Mode.Eq,
) : LocalSearchFactor {

    enum class Mode { Eq, AtLeast, AtMost }

    init {
        require(xs.isNotEmpty()) { "nvalue: empty xs" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(n)

    /** Maintains a per-value count over the assignment. `distinctCount` = number of values
     *  whose count is > 0. */
    private class State(val counts: HashMap<Int, Int>, var distinctCount: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = HashMap<Int, Int>()
        var distinct = 0
        for (v in xs) {
            val value = state.assignment.intValue(v)
            val prev = counts[value] ?: 0
            counts[value] = prev + 1
            if (prev == 0) distinct++
        }
        state.refPayload[factorId] = State(counts, distinct)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        val nVal = state.assignment.intValue(n)
        return when (mode) {
            Mode.Eq -> nVal != s.distinctCount
            Mode.AtLeast -> nVal > s.distinctCount
            Mode.AtMost -> nVal < s.distinctCount
        }
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = isViolatedInternal(s, state.assignment.intValue(n))
        val newDistinct = simulateDistinct(state, s, intVar, newValue)
        val newN = if (intVar == n) newValue else state.assignment.intValue(n)
        val willViolate = isViolatedInternal(newDistinct, newN)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun isViolatedInternal(s: State, nVal: Int): Boolean = when (mode) {
        Mode.Eq -> nVal != s.distinctCount
        Mode.AtLeast -> nVal > s.distinctCount
        Mode.AtMost -> nVal < s.distinctCount
    }

    private fun isViolatedInternal(distinct: Int, nVal: Int): Boolean = when (mode) {
        Mode.Eq -> nVal != distinct
        Mode.AtLeast -> nVal > distinct
        Mode.AtMost -> nVal < distinct
    }

    private fun simulateDistinct(state: LocalSearchState, s: State, intVar: Int, newValue: Int): Int {
        // intVar's previous value affects xs only if it's one of the operands. If it's n
        // (not an xs operand), distinct count unchanged.
        var occurrences = 0
        for (v in xs) if (v == intVar) occurrences++
        if (occurrences == 0) return s.distinctCount
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return s.distinctCount
        var distinct = s.distinctCount
        val oldCount = s.counts[old] ?: 0
        // After removing `occurrences` of `old`: count' = oldCount - occurrences.
        if (oldCount - occurrences == 0) distinct--
        val newCount = s.counts[newValue] ?: 0
        if (newCount == 0) distinct++
        return distinct
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val nVal = state.assignment.intValue(n)
        val wasViolated = isViolatedInternal(s, nVal)
        var occurrences = 0
        for (v in xs) if (v == intVar) occurrences++
        if (occurrences > 0) {
            val oldCount = s.counts[oldValue] ?: 0
            val after = oldCount - occurrences
            if (after == 0) { s.counts.remove(oldValue); s.distinctCount-- }
            else s.counts[oldValue] = after
            val newCount = s.counts[cur] ?: 0
            if (newCount == 0) s.distinctCount++
            s.counts[cur] = newCount + occurrences
        }
        val nowViolated = isViolatedInternal(s, state.assignment.intValue(n))
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /**
     * Bounds on `n`:
     *  - upper: number of distinct values in union of all xs domains.
     *  - lower: count of distinct singleton-pinned values.
     *
     * Stronger inference (Hall-style under [Mode.AtMost]) lands in the next propagation pass.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Upper bound: |∪ dom(xs[i])| — domain enumeration via forEach.
        val unionValues = HashSet<Int>()
        for (v in xs) state.intDomains[v].forEach { unionValues.add(it) }
        val maxDistinct = unionValues.size
        // Lower bound: count of distinct singletons.
        val singletons = HashSet<Int>()
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min == d.max) singletons.add(d.min)
        }
        val minDistinct = singletons.size
        val ant = state.composeIntVarAntecedents(xs)
        when (mode) {
            Mode.Eq -> {
                if (!state.tightenIntMin(n, minDistinct, ant)) return false
                if (!state.tightenIntMax(n, maxDistinct, ant)) return false
            }
            Mode.AtLeast -> {
                if (!state.tightenIntMax(n, maxDistinct, ant)) return false
            }
            Mode.AtMost -> {
                if (!state.tightenIntMin(n, minDistinct, ant)) return false
            }
        }
        return true
    }
}
