package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList

/**
 * `value_precede(s, t, xs)` — if value `t` appears in [xs], then the first occurrence of `s`
 * precedes the first occurrence of `t`. Standard symmetry-breaking constraint on
 * permutation / colour-assignment classes.
 *
 * `value_precede_chain(values, xs)` is built as a sequence of `ValuePrecede(values[i],
 * values[i+1], xs)` factors at the FZN-dispatch level — one factor per consecutive pair
 * — so chain semantics fall out for free.
 *
 * Propagation runs the two standard value-precedence rules each call:
 *  - **no premature `t`**: let `p` be the earliest position whose domain still contains `s`.
 *    No position `i ≤ p` can hold `t` (there is no room for a preceding `s`), so `t` is
 *    pruned from `dom(xs[0..p])`. If `s` is impossible everywhere, `t` is forbidden entirely.
 *  - **forced `t` demands an earlier `s`**: if some position is pinned to `t`, an `s` must
 *    occupy an earlier position; zero candidates ⟹ UNSAT, exactly one ⟹ force it to `s`.
 * This prunes during search rather than acting as a leaf-only sanity check.
 */
class ValuePrecede(
    /** The value that must first appear before [t]. */
    val s: Int,
    /** The value whose first occurrence must follow [s]. */
    val t: Int,
    /** The sequence variable ids. */
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

    /** Repair: at the first xs[i] holding `t` before any `s` appeared, propose moves to
     *  either drop xs[i] off `t` (replace with anything else in its domain) or to set
     *  some xs[j] (j < i) to `s` so the precedence holds. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (satisfied(state)) return
        var firstTAt = -1
        for (i in xs.indices) {
            val v = state.assignment.intValue(xs[i])
            if (v == s) return // satisfied — shouldn't reach here given !satisfied above
            if (v == t) {
                firstTAt = i
                break
            }
        }
        if (firstTAt < 0) return
        // 1. Replace xs[firstTAt] with anything that's not t.
        val xi = xs[firstTAt]
        val d = state.problem.intDomains[xi]
        val cur = state.assignment.intValue(xi)
        d.forEach { vv -> if (vv != t && vv != cur) sink.addChannelingIntSet(state, xi, vv) }
        // 2. Set some xs[j] with j < firstTAt to s.
        for (j in 0 until firstTAt) {
            val xj = xs[j]
            val curJ = state.assignment.intValue(xj)
            if (curJ != s && s in state.problem.intDomains[xj]) sink.addChannelingIntSet(state, xj, s)
        }
    }

    private fun satisfied(state: LocalSearchState): Boolean = walk { state.assignment.intValue(it) }

    private fun satisfiedWithOverride(state: LocalSearchState, intVar: Int, override: Int): Boolean =
        walk { x -> if (x == intVar) override else state.assignment.intValue(x) }

    private inline fun walk(getValue: (Int) -> Int): Boolean {
        for (x in xs) {
            val v = getValue(x)
            // First `t` before first `s` → violated.
            if (v == t) return false
            if (v == s) return true // first `s` encountered → constraint satisfied
        }
        // Neither `s` nor `t` ever appeared → vacuously true.
        return true
    }

    /** Hole-aware conflict reason: the rules turn on which positions can/can't hold `s` or
     *  `t`, which holey domains decide, so cite filtered bounds *and* interior holes. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, xs)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Earliest position whose domain can still hold `s` (xs.size if none).
        var firstSPossible = xs.size
        for (i in xs.indices) {
            if (s in state.intDomains[xs[i]]) {
                firstSPossible = i
                break
            }
        }
        val ant = collectHoleAndBoundAntecedents(state, xs)
        // Rule 1: no `t` at any position ≤ firstSPossible — no room for a preceding `s`.
        // (firstSPossible == xs.size ⟹ `s` impossible everywhere ⟹ `t` forbidden everywhere.)
        for (i in xs.indices) {
            if (i > firstSPossible) break
            if (!state.excludeIntValue(xs[i], t, ant)) return false // emptied a pinned-`t` slot
        }
        // Rule 2: if a position is pinned to `t`, an `s` must precede it.
        var firstTForced = -1
        for (i in xs.indices) {
            val d = state.intDomains[xs[i]]
            if (d.min == d.max && d.min == t) {
                firstTForced = i
                break
            }
        }
        if (firstTForced >= 0) {
            var sCandidate = -1
            var sCount = 0
            for (j in 0 until firstTForced) {
                if (s in state.intDomains[xs[j]]) {
                    sCount++
                    sCandidate = j
                }
            }
            if (sCount == 0) return false // forced `t` with no possible preceding `s`
            if (sCount == 1) {
                // The sole pre-`t` candidate must take `s`: drop every other value.
                val xj = xs[sCandidate]
                val drop = IntArrayList()
                state.intDomains[xj].forEach { if (it != s) drop.add(it) }
                for (k in 0 until drop.size) {
                    if (!state.excludeIntValue(xj, drop[k], ant)) return false
                }
            }
        }
        return true
    }
}
