package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `result = max(xs)` or `result = min(xs)` — covers MiniZinc's `array_int_maximum(result,
 * xs)` / `array_int_minimum(result, xs)`. Mode selected by [max].
 *
 * Propagation tightens [result] against the bound of [xs] and pushes back from [result]
 * to every `xs[i]` (for max: every `xs[i].max <= result.max`; for min the dual). LS keeps
 * a payload holding the index of the current best operand and its value, with a fallback
 * full scan when the best slot changes.
 */
class ArrayMinMax(
    val result: Int,
    val xs: IntArray,
    val max: Boolean,
) : LocalSearchFactor {

    init {
        require(xs.isNotEmpty()) { "ArrayMinMax needs at least one operand" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(result)

    /** Cached best (min/max) value across `xs` under the current assignment. */
    private class State(var bestValue: Int)

    private fun cmp(a: Int, b: Int): Boolean = if (max) a > b else a < b

    private fun computeBest(state: LocalSearchState): Int {
        var best = state.assignment.intValue(xs[0])
        for (i in 1 until xs.size) {
            val v = state.assignment.intValue(xs[i])
            if (cmp(v, best)) best = v
        }
        return best
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.refPayload[factorId] = State(computeBest(state))
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return state.assignment.intValue(result) != s.bestValue
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = state.assignment.intValue(result) != s.bestValue
        // Simulate: compute new best with intVar holding newValue.
        val newBest = simulateBest(state, intVar, newValue)
        val newResult = if (intVar == result) newValue else state.assignment.intValue(result)
        val willViolate = newResult != newBest
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    private fun simulateBest(state: LocalSearchState, intVar: Int, newValue: Int): Int {
        var best = Int.MIN_VALUE
        var init = false
        for (v in xs) {
            val current = if (v == intVar) newValue else state.assignment.intValue(v)
            if (!init) { best = current; init = true }
            else if (cmp(current, best)) best = current
        }
        return best
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = state.assignment.intValue(result) != s.bestValue
        // Recompute best from scratch — payload is just a single int, no incremental ds.
        s.bestValue = computeBest(state)
        val nowViolated = state.assignment.intValue(result) != s.bestValue
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /** Repair: snap `result` to the current best xs value (the most reliable single move),
     *  and additionally propose moves that bring an xs element to `result`'s current value
     *  (so the constraint holds via the value-side rather than the result-side). */
    override fun proposeRepairMoves(
        state: LocalSearchState,
        factorId: Int,
        sink: com.eignex.klause.solver.localsearch.MoveSink,
    ) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as State
        val best = s.bestValue
        val rDom = state.problem.intDomains[result]
        if (best in rDom) sink.addChannelingIntSet(state, result, best)
        val rv = state.assignment.intValue(result)
        // Push an xs element toward rv so the best across xs becomes rv (when rv is more
        // extreme than current best). Pick any xs[i] in whose domain rv lies.
        if ((max && rv > best) || (!max && rv < best)) {
            for (v in xs) {
                if (rv in state.problem.intDomains[v] && rv != state.assignment.intValue(v)) {
                    sink.addChannelingIntSet(state, v, rv); break
                }
            }
        }
    }

    /** Bound-only conflict reason. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val antXs = state.composeIntVarAtomAntecedents(xs)
        val antResult = state.composeIntVarAtomAntecedents(intArrayOf(result))
        if (max) {
            var hiBound = Int.MIN_VALUE
            var loBound = Int.MIN_VALUE
            for (i in xs) {
                val d = state.intDomains[i]
                if (d.max > hiBound) hiBound = d.max
                if (d.min > loBound) loBound = d.min
            }
            if (!state.tightenIntMax(result, hiBound, antXs)) return false
            if (!state.tightenIntMin(result, loBound, antXs)) return false
            val rMax = state.intDomains[result].max
            for (i in xs) if (!state.tightenIntMax(i, rMax, antResult)) return false
        } else {
            var loBound = Int.MAX_VALUE
            var hiBound = Int.MAX_VALUE
            for (i in xs) {
                val d = state.intDomains[i]
                if (d.min < loBound) loBound = d.min
                if (d.max < hiBound) hiBound = d.max
            }
            if (!state.tightenIntMin(result, loBound, antXs)) return false
            if (!state.tightenIntMax(result, hiBound, antXs)) return false
            val rMin = state.intDomains[result].min
            for (i in xs) if (!state.tightenIntMin(i, rMin, antResult)) return false
        }
        return true
    }
}
