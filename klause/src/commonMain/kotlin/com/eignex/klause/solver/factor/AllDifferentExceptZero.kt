package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `alldifferent_except_0(xs)` — `xs[i] != xs[j]` for every pair `i < j` *unless* one of the
 * two values is `0`. Common in sparse-permutation modelling: zero stands in for "absent",
 * and non-zero values must be unique.
 *
 * Decomposed propagation in this first cut: detect singleton conflicts on non-zero values
 * (two vars pinned to the same non-zero value → fail). LS counts pairs of equal non-zero
 * values. Strong (Régin-style) propagation lands when full propagator strength is in
 * scope (next step).
 */
class AllDifferentExceptZero(
    val xs: IntArray,
) : LocalSearchFactor {

    init {
        require(xs.size >= 2) { "AllDifferentExceptZero needs at least two variables" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    /** Per-value count among non-zero values. `violatedPairs` is the number of (i, j) with
     *  i < j and xs[i] = xs[j] != 0; equivalently Σ_v max(0, count[v] - 1) over v != 0. */
    private class State(val counts: HashMap<Int, Int>, var violatedPairs: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = HashMap<Int, Int>()
        var bad = 0
        for (v in xs) {
            val value = state.assignment.intValue(v)
            if (value == 0) continue
            val prev = counts[value] ?: 0
            counts[value] = prev + 1
            if (prev >= 1) bad++
        }
        state.refPayload[factorId] = State(counts, bad)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return s.violatedPairs > 0
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val old = state.assignment.intValue(intVar)
        if (old == newValue) return 0
        // Count how many vars in xs currently hold `intVar`. AllDifferentExceptZero typically
        // sees each var once, but the factor's interface allows repetition.
        var occurrences = 0
        for (v in xs) if (v == intVar) occurrences++
        if (occurrences == 0) return 0
        var bad = s.violatedPairs
        if (old != 0) {
            val cnt = s.counts[old] ?: 0
            val after = cnt - occurrences
            // Pairs lost = cnt-1 + cnt-2 + … + after = cnt*(cnt-1)/2 - after*(after-1)/2
            bad -= pairsAt(cnt) - pairsAt(maxOf(after, 0))
        }
        if (newValue != 0) {
            val cnt = s.counts[newValue] ?: 0
            val after = cnt + occurrences
            bad += pairsAt(after) - pairsAt(cnt)
        }
        val wasViolated = s.violatedPairs > 0
        val willViolate = bad > 0
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        var occurrences = 0
        for (v in xs) if (v == intVar) occurrences++
        if (occurrences == 0) return 0
        val wasViolated = s.violatedPairs > 0
        if (oldValue != 0) {
            val cnt = s.counts[oldValue] ?: 0
            val after = cnt - occurrences
            s.violatedPairs -= pairsAt(cnt) - pairsAt(maxOf(after, 0))
            if (after <= 0) s.counts.remove(oldValue) else s.counts[oldValue] = after
        }
        if (cur != 0) {
            val cnt = s.counts[cur] ?: 0
            val after = cnt + occurrences
            s.violatedPairs += pairsAt(after) - pairsAt(cnt)
            s.counts[cur] = after
        }
        val nowViolated = s.violatedPairs > 0
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /** Count of unordered pairs from [k] indistinguishable elements: k * (k-1) / 2. */
    private fun pairsAt(k: Int): Int = if (k <= 1) 0 else k * (k - 1) / 2

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Singleton conflicts on non-zero values.
        val taken = HashSet<Int>()
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min != d.max) continue
            if (d.min == 0) continue
            if (!taken.add(d.min)) return false
        }
        // Punch every singleton-taken value out of every other var's domain.
        if (taken.isNotEmpty()) {
            val ant = state.composeIntVarAntecedents(xs)
            for (v in xs) {
                val d = state.intDomains[v]
                if (d.min == d.max) continue
                for (t in taken) {
                    if (t < d.min || t > d.max) continue
                    if (!state.excludeIntValue(v, t, ant)) return false
                }
            }
        }
        return true
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val s = state.refPayload[factorId] as State
        if (s.violatedPairs == 0) return
        // Find a duplicated non-zero value, then propose nudging one of its holders.
        var target: Int = Int.MIN_VALUE
        for ((value, count) in s.counts) {
            if (count >= 2) { target = value; break }
        }
        if (target == Int.MIN_VALUE) return
        for (v in xs) {
            if (state.assignment.intValue(v) != target) continue
            val d = state.problem.intDomains[v]
            // Try setting to 0 if 0 ∈ d.
            if (0 in d) sink.addIntSet(v, 0)
            // ±1 nudges.
            if (target > d.min && (target - 1) in d) sink.addIntSet(v, target - 1)
            if (target < d.max && (target + 1) in d) sink.addIntSet(v, target + 1)
            return
        }
    }
}
