package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `knapsack(weights, profits, xs, w, p)` — `w = Σ weights[i] · xs[i]` and `p = Σ profits[i]
 * · xs[i]`. The two simultaneous linear equations are the constraint; the "capacity ≤ W"
 * convention is enforced by [w]'s declared domain upper bound, and "maximise profit" by
 * the solve directive consuming [p].
 *
 * Decomposed propagation in this first cut: bound-tighten [w] and [p] from the per-element
 * `weights[i] · domain(xs[i])` and `profits[i] · domain(xs[i])` ranges. LP-relaxation-driven
 * pruning lands when full propagation strength is in scope (next step).
 */
class Knapsack(
    val weights: IntArray,
    val profits: IntArray,
    val xs: IntArray,
    val w: Int,
    val p: Int,
) : LocalSearchFactor {

    init {
        require(weights.size == xs.size) { "knapsack: weights/xs size mismatch" }
        require(profits.size == xs.size) { "knapsack: profits/xs size mismatch" }
        require(xs.isNotEmpty()) { "knapsack: empty xs" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(w, p)

    private class State(var currentWeight: Int, var currentProfit: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var ww = 0; var pp = 0
        for (i in xs.indices) {
            val v = state.assignment.intValue(xs[i])
            ww += weights[i] * v
            pp += profits[i] * v
        }
        state.refPayload[factorId] = State(ww, pp)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return state.assignment.intValue(w) != s.currentWeight ||
            state.assignment.intValue(p) != s.currentProfit
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = isViolated(state, factorId)
        var dWeight = 0
        var dProfit = 0
        for (i in xs.indices) {
            if (xs[i] != intVar) continue
            val old = state.assignment.intValue(intVar)
            val deltaCell = newValue - old
            dWeight += weights[i] * deltaCell
            dProfit += profits[i] * deltaCell
        }
        val newW = s.currentWeight + dWeight
        val newP = s.currentProfit + dProfit
        val newWvar = if (intVar == w) newValue else state.assignment.intValue(w)
        val newPvar = if (intVar == p) newValue else state.assignment.intValue(p)
        val willViolate = newWvar != newW || newPvar != newP
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        // Compute pre-update violation by reversing the change on a copy.
        val wasViolated = run {
            val priorW = s.currentWeight - xs.indices.sumOf { if (xs[it] == intVar) weights[it] * (cur - oldValue) else 0 }
            val priorP = s.currentProfit - xs.indices.sumOf { if (xs[it] == intVar) profits[it] * (cur - oldValue) else 0 }
            val wVar = if (intVar == w) oldValue else state.assignment.intValue(w)
            val pVar = if (intVar == p) oldValue else state.assignment.intValue(p)
            wVar != priorW || pVar != priorP
        }
        // Apply update to maintained sums.
        for (i in xs.indices) {
            if (xs[i] != intVar) continue
            val delta = cur - oldValue
            s.currentWeight += weights[i] * delta
            s.currentProfit += profits[i] * delta
        }
        val nowViolated = isViolated(state, factorId)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    /**
     * Bound-tighten `w` and `p` against the per-element coefficient ranges. For
     * coefficient `c_i ≥ 0`, the contribution range is `[c_i · d_i.min, c_i · d_i.max]`;
     * for `c_i < 0` it's `[c_i · d_i.max, c_i · d_i.min]`. Sum the per-element extremes
     * to bound the totals.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var wLo = 0L; var wHi = 0L
        var pLo = 0L; var pHi = 0L
        for (i in xs.indices) {
            val d = state.intDomains[xs[i]]
            val wc = weights[i].toLong()
            val pc = profits[i].toLong()
            if (wc >= 0) { wLo += wc * d.min; wHi += wc * d.max }
            else        { wLo += wc * d.max; wHi += wc * d.min }
            if (pc >= 0) { pLo += pc * d.min; pHi += pc * d.max }
            else        { pLo += pc * d.max; pHi += pc * d.min }
        }
        if (wLo > Int.MAX_VALUE || wHi < Int.MIN_VALUE) return false
        if (pLo > Int.MAX_VALUE || pHi < Int.MIN_VALUE) return false
        val ant = state.composeIntVarAntecedents(xs)
        if (!state.tightenIntMin(w, wLo.coerceAtLeast(Int.MIN_VALUE.toLong()).toInt(), ant)) return false
        if (!state.tightenIntMax(w, wHi.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), ant)) return false
        if (!state.tightenIntMin(p, pLo.coerceAtLeast(Int.MIN_VALUE.toLong()).toInt(), ant)) return false
        if (!state.tightenIntMax(p, pHi.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), ant)) return false
        return true
    }
}
