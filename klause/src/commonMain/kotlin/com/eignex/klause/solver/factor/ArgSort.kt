package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `arg_sort(values, perm, permOffset=0)` — [perm] is a permutation of
 * `[permOffset, permOffset+n-1]` such that the sequence `values[perm[i] − permOffset]`
 * is non-decreasing, with ties broken by smaller index.
 *
 * First-cut propagation:
 *  - Bound-check that [perm] entries lie in `[permOffset, permOffset+n-1]`.
 *  - Pairwise NE on [perm] (all-different).
 *  - When all [perm] entries are pinned, verify sorted-ness directly and propagate
 *    bound-tightenings from [values] back to the ordering implied by perm.
 *
 * Stronger Régin-style propagation (matching-based) is left as a follow-up; the basic
 * factor here already beats the per-pair-Element decomposition on conflict-attribution
 * locality.
 */
class ArgSort(
    val values: IntArray,
    val perm: IntArray,
    val permOffset: Int = 0,
) : LocalSearchFactor {

    init {
        require(values.size == perm.size) { "ArgSort: values and perm length mismatch" }
        require(values.isNotEmpty()) { "ArgSort: empty arrays" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = values + perm

    override fun initialize(state: LocalSearchState, factorId: Int) {}

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val n = perm.size
        val permVals = IntArray(n) { state.assignment.intValue(perm[it]) - permOffset }
        // perm permutation of [0, n-1].
        val seen = BooleanArray(n)
        for (p in permVals) {
            if (p < 0 || p >= n) return true
            if (seen[p]) return true
            seen[p] = true
        }
        // Values at perm[i] non-decreasing with tie-break by perm index.
        for (i in 0 until n - 1) {
            val a = state.assignment.intValue(values[permVals[i]])
            val b = state.assignment.intValue(values[permVals[i + 1]])
            if (a > b) return true
            if (a == b && permVals[i] >= permVals[i + 1]) return true
        }
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val was = isViolated(state, factorId)
        val n = perm.size
        val permVals = IntArray(n) { i ->
            val v = if (perm[i] == intVar) newValue else state.assignment.intValue(perm[i])
            v - permOffset
        }
        val vals = IntArray(n) { i ->
            if (values[i] == intVar) newValue else state.assignment.intValue(values[i])
        }
        var will = false
        val seen = BooleanArray(n)
        for (p in permVals) {
            if (p < 0 || p >= n) { will = true; break }
            if (seen[p]) { will = true; break }
            seen[p] = true
        }
        if (!will) {
            for (i in 0 until n - 1) {
                val a = vals[permVals[i]]
                val b = vals[permVals[i + 1]]
                if (a > b) { will = true; break }
                if (a == b && permVals[i] >= permVals[i + 1]) { will = true; break }
            }
        }
        return (if (will) 1 else 0) - (if (was) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = perm.size
        val ant = state.composeIntVarAtomAntecedents(intVars)
        // perm entries in range.
        for (p in perm) {
            if (!state.tightenIntMin(p, permOffset, ant)) return false
            if (!state.tightenIntMax(p, permOffset + n - 1, ant)) return false
        }
        // Pairwise distinctness via singleton-taken filter (same pattern as AllDifferent).
        val taken = HashSet<Int>()
        for (p in perm) {
            val d = state.intDomains[p]
            if (d.min != d.max) continue
            if (!taken.add(d.min)) return false
        }
        if (taken.isNotEmpty()) {
            for (p in perm) {
                val d = state.intDomains[p]
                if (d.min == d.max) continue
                for (t in taken) {
                    if (t < d.min || t > d.max) continue
                    if (!state.excludeIntValue(p, t, ant)) return false
                }
            }
        }
        // Pin-based sorted check (only fires when perm is fully assigned).
        var allPinned = true
        for (p in perm) if (state.intDomains[p].min != state.intDomains[p].max) { allPinned = false; break }
        if (allPinned) {
            val pv = IntArray(n) { state.intDomains[perm[it]].min - permOffset }
            for (i in 0 until n - 1) {
                val a = pv[i]; val b = pv[i + 1]
                // values[a] ≤ values[b] always; and if values equal, a < b.
                if (!state.tightenIntMax(values[a], state.intDomains[values[b]].max, ant)) return false
                if (!state.tightenIntMin(values[b], state.intDomains[values[a]].min, ant)) return false
                if (a >= b) {
                    // Strict tie-break: values[a] < values[b].
                    if (!state.tightenIntMax(values[a], state.intDomains[values[b]].max - 1, ant)) return false
                    if (!state.tightenIntMin(values[b], state.intDomains[values[a]].min + 1, ant)) return false
                }
            }
        }
        return true
    }
}
