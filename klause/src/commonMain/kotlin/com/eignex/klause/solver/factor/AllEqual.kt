package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `all_equal_int(xs)` — every `xs[i]` takes the same value. Trivially equivalent to a
 * chain of pairwise `xs[i] = xs[0]` equalities, but the dedicated factor lets the
 * engine propagate the intersection of domains in one pass: pick the max of every
 * `xs[i].min` as the common lower bound and the min of every `xs[i].max` as the
 * common upper bound, then push back to every operand.
 */
class AllEqual(val xs: IntArray) : LocalSearchFactor {

    init {
        require(xs.size >= 2) { "all_equal needs at least two variables" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs

    override fun initialize(state: LocalSearchState, factorId: Int) {}

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val v0 = state.assignment.intValue(xs[0])
        for (i in 1 until xs.size) if (state.assignment.intValue(xs[i]) != v0) return true
        return false
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val wasViolated = isViolated(state, factorId)
        val v0 = if (xs[0] == intVar) newValue else state.assignment.intValue(xs[0])
        var willViolate = false
        for (i in xs.indices) {
            val v = if (xs[i] == intVar) newValue else state.assignment.intValue(xs[i])
            if (v != v0) { willViolate = true; break }
        }
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    /** Pick the most common current value across [xs] (mode) as the consensus target;
     *  for every dissenting var whose domain contains the target, propose snapping to it.
     *  Falls back to the secondary mode if the primary target is out of some domains. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val counts = HashMap<Int, Int>(xs.size)
        for (v in xs) {
            val cur = state.assignment.intValue(v)
            counts[cur] = (counts[cur] ?: 0) + 1
        }
        // Try the top-2 most-frequent values; if first is rejected by some domain, the
        // second often succeeds. Keeps the proposal-set small.
        val ranked = counts.entries.sortedByDescending { it.value }
        for ((target, _) in ranked.take(2)) {
            for (v in xs) {
                val cur = state.assignment.intValue(v)
                if (cur != target && target in state.problem.intDomains[v]) {
                    sink.addIntSet(v, target)
                }
            }
        }
    }

    /** Bound-only conflict reason. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, xs, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Common domain = ∩ dom(xs[i]) — implement as [maxOfMins, minOfMaxes].
        var commonMin = Int.MIN_VALUE
        var commonMax = Int.MAX_VALUE
        for (v in xs) {
            val d = state.intDomains[v]
            if (d.min > commonMin) commonMin = d.min
            if (d.max < commonMax) commonMax = d.max
        }
        if (commonMin > commonMax) return false
        val ant = state.composeIntVarAtomAntecedents(xs)
        for (v in xs) {
            if (!state.tightenIntMin(v, commonMin, ant)) return false
            if (!state.tightenIntMax(v, commonMax, ant)) return false
        }
        return true
    }
}
