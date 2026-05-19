package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `symmetric_all_different(xs)` — `xs` is a self-inverse permutation: `xs[xs[i]] = i` for
 * every `i`. Strictly stronger than `all_different` (which just demands distinctness):
 * each value also points back to its pointer.
 *
 * [indexOffset] is the value `xs[0]` would take to mean position 0 — typically `1` for
 * the MZN 1-based default.
 *
 * Propagation in this first cut: all-different singleton-conflict detection inherited
 * from `AllDifferent`, plus a self-inverse check on singletons. Régin-style flow + sym
 * propagator lands when full strength is in scope.
 */
class SymmetricAllDifferent(
    val xs: IntArray,
    val indexOffset: Int = 0,
) : LocalSearchFactor {

    init {
        require(xs.isNotEmpty()) { "symmetric_all_different: empty xs" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    override fun initialize(state: LocalSearchState, factorId: Int) {}

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val seen = HashSet<Int>()
        for (i in xs.indices) {
            val v = state.assignment.intValue(xs[i])
            if (!seen.add(v)) return true
            // Self-inverse: xs[xs[i] - offset] = i + offset.
            val target = v - indexOffset
            if (target !in xs.indices) return true
            if (state.assignment.intValue(xs[target]) != i + indexOffset) return true
        }
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = isViolated(state, factorId)
        val seen = HashSet<Int>()
        var willViolate = false
        for (i in xs.indices) {
            val v = if (xs[i] == intVar) newValue else state.assignment.intValue(xs[i])
            if (!seen.add(v)) { willViolate = true; break }
            val target = v - indexOffset
            if (target !in xs.indices) { willViolate = true; break }
            val backVal = if (xs[target] == intVar) newValue else state.assignment.intValue(xs[target])
            if (backVal != i + indexOffset) { willViolate = true; break }
        }
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Tighten each xs[i] into the legal index range.
        val lo = indexOffset
        val hi = indexOffset + xs.size - 1
        for (v in xs) {
            if (!state.tightenIntMin(v, lo)) return false
            if (!state.tightenIntMax(v, hi)) return false
        }
        // AllDifferent singleton conflict.
        val taken = HashSet<Int>()
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min != d.max) continue
            if (!taken.add(d.min)) return false
        }
        // Self-inverse forcing on singletons: xs[i] = j (singleton) → xs[j - offset] = i + offset.
        for (i in xs.indices) {
            val d = state.intDomains[xs[i]]
            if (d.min != d.max) continue
            val target = d.min - indexOffset
            if (target !in xs.indices) return false
            val mirror = i + indexOffset
            val ant = state.composeIntVarAntecedents(intArrayOf(xs[i]))
            if (!state.tightenIntMin(xs[target], mirror, ant)) return false
            if (!state.tightenIntMax(xs[target], mirror, ant)) return false
        }
        return true
    }
}
