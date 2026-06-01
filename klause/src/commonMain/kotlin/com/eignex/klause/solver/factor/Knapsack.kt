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
 * Decomposed propagation: bound-tighten [w] and [p] from the per-element
 * `weights[i] · domain(xs[i])` and `profits[i] · domain(xs[i])` ranges.
 */
class Knapsack(val weights: IntArray, val profits: IntArray, val xs: IntArray, val w: Int, val p: Int) :
    LocalSearchFactor {

    init {
        require(weights.size == xs.size) { "knapsack: weights/xs size mismatch" }
        require(profits.size == xs.size) { "knapsack: profits/xs size mismatch" }
        require(xs.isNotEmpty()) { "knapsack: empty xs" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = xs + intArrayOf(w, p)

    private class State(var currentWeight: Int, var currentProfit: Int)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var ww = 0
        var pp = 0
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

    /** Repair: snap `w` / `p` to the current totals; if w/p are pinned and the totals
     *  diverge, propose flipping a high-leverage xs[i] in the direction that closes the gap. */
    override fun proposeRepairMoves(
        state: LocalSearchState,
        factorId: Int,
        sink: com.eignex.klause.solver.localsearch.MoveSink,
    ) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as State
        val wDom = state.problem.intDomains[w]
        if (s.currentWeight in wDom && s.currentWeight != state.assignment.intValue(w)) {
            sink.addChannelingIntSet(state, w, s.currentWeight)
        }
        val pDom = state.problem.intDomains[p]
        if (s.currentProfit in pDom && s.currentProfit != state.assignment.intValue(p)) {
            sink.addChannelingIntSet(state, p, s.currentProfit)
        }
        // For each xs[i], if w-var requires lower weight, propose decreasing xs[i] when
        // weights[i] > 0; symmetric for profit.
        val wTarget = state.assignment.intValue(w)
        val wGap = wTarget - s.currentWeight // positive: need more weight
        if (wGap != 0) {
            for (i in xs.indices) {
                if (weights[i] == 0) continue
                val xi = xs[i]
                val cur = state.assignment.intValue(xi)
                val d = state.problem.intDomains[xi]
                val wantIncrease = (weights[i] > 0) == (wGap > 0)
                val candidate = if (wantIncrease) cur + 1 else cur - 1
                if (candidate in d) sink.addChannelingIntSet(state, xi, candidate)
            }
        }
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        // Compute pre-update violation by reversing the change on a copy.
        val wasViolated = run {
            val priorW =
                s.currentWeight - xs.indices.sumOf { if (xs[it] == intVar) weights[it] * (cur - oldValue) else 0 }
            val priorP =
                s.currentProfit - xs.indices.sumOf { if (xs[it] == intVar) profits[it] * (cur - oldValue) else 0 }
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
     * Knapsack reduces to two simultaneous linear equalities:
     *  - `Σ weights[i] · xs[i] - w = 0`
     *  - `Σ profits[i] · xs[i] - p = 0`
     *
     * Both equalities are bound-propagated via the shared [propagateLinearBounds] routine,
     * which not only tightens [w] / [p] from per-element coefficient ranges but also
     * propagates back: knowing `w`'s upper bound prunes high-end values of each `xs[i]`
     * whose minimum forced contribution exceeds the remaining slack. This subsumes the
     * old one-way bound-tighten-only behaviour.
     */
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val n = xs.size
        // Linear form: append the sum-variable with coefficient -1 so the equality reads
        // `Σ coeffs · vars = 0`.
        val weightCoeffs = IntArray(n + 1).also {
            weights.copyInto(it)
            it[n] = -1
        }
        val profitCoeffs = IntArray(n + 1).also {
            profits.copyInto(it)
            it[n] = -1
        }
        val weightVars = IntArray(n + 1).also {
            xs.copyInto(it)
            it[n] = w
        }
        val profitVars = IntArray(n + 1).also {
            xs.copyInto(it)
            it[n] = p
        }
        if (!propagateLinearBounds(state, weightCoeffs, weightVars, LinearOp.EQ, 0L)) return false
        if (!propagateLinearBounds(state, profitCoeffs, profitVars, LinearOp.EQ, 0L)) return false
        return true
    }

    /** Reason on conflict: full participating-var bound atoms. The two-equality view
     *  pins the conflict on the joint domain bounds, like [Linear]. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)
}
