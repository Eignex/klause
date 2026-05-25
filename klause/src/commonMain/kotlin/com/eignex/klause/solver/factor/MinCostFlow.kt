package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Min-cost network flow factor: for each node `n`, `Σ flow[a ∈ in(n)] − Σ flow[a ∈ out(n)]
 *  = balance[n]`. When [cost] is non-negative, also enforces `Σ weight[a] · flow[a] = cost`.
 *
 * Propagation in this first cut beyond the linear decomposition:
 *  - Per-node feasibility check using current flow-var bounds (if min-inflow > max-outflow +
 *    balance, fail; symmetric for the other side).
 *  - Reduced-cost based pruning on arcs is left as a follow-up (SSP / cost-scaling).
 *
 * The decomposition (CompilerGlobalsLowering.decomposeNetworkFlow*) still emits the
 * per-node balance Linear factors, so the linear bound propagation is still in play.
 * This factor adds an early-fail check at the network level that's quicker than the
 * linear decomposition catching the conflict.
 */
class MinCostFlow(
    val numNodes: Int,
    val arcFrom: IntArray,
    val arcTo: IntArray,
    val balance: IntArray,
    val flow: IntArray,
    val weight: IntArray?,
    val cost: Int,  // -1 when no cost variable
    val nodeOffset: Int = 0,
) : LocalSearchFactor {

    init {
        require(arcFrom.size == arcTo.size && arcFrom.size == flow.size) {
            "MinCostFlow: arcFrom/arcTo/flow size mismatch"
        }
        require(balance.size == numNodes) { "MinCostFlow: balance size" }
        if (weight != null) require(weight.size == flow.size) { "MinCostFlow: weight size" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = if (cost >= 0) flow + intArrayOf(cost) else flow.copyOf()

    private val inArcs: Array<IntArray> = run {
        val acc = Array(numNodes) { mutableListOf<Int>() }
        for (a in arcTo.indices) acc[arcTo[a] - nodeOffset].add(a)
        Array(numNodes) { acc[it].toIntArray() }
    }
    private val outArcs: Array<IntArray> = run {
        val acc = Array(numNodes) { mutableListOf<Int>() }
        for (a in arcFrom.indices) acc[arcFrom[a] - nodeOffset].add(a)
        Array(numNodes) { acc[it].toIntArray() }
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {}
    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = false
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int = 0
    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.composeIntVarAtomAntecedents(intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val ant = state.composeIntVarAtomAntecedents(intVars)
        // Per-node: tighten inflow / outflow range using balance.
        for (n in 0 until numNodes) {
            // inflow min - outflow max ≤ balance ≤ inflow max - outflow min.
            var inMin = 0L; var inMax = 0L
            for (a in inArcs[n]) { val d = state.intDomains[flow[a]]; inMin += d.min; inMax += d.max }
            var outMin = 0L; var outMax = 0L
            for (a in outArcs[n]) { val d = state.intDomains[flow[a]]; outMin += d.min; outMax += d.max }
            val balN = balance[n].toLong()
            // Feasibility: inMin - outMax ≤ balN ≤ inMax - outMin.
            if (inMin - outMax > balN) return false
            if (inMax - outMin < balN) return false
            // Tighten each arc's bounds using the balance equation.
            for (a in inArcs[n]) {
                val d = state.intDomains[flow[a]]
                // inMin' = inMin - d.min + new_min, want inMin' - outMax ≤ balN  →  new_min ≤ outMax + balN - (inMin - d.min) → new max upper.
                val maxAllowed = (outMax + balN - (inMin - d.min)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (!state.tightenIntMax(flow[a], maxAllowed, ant)) return false
                val minRequired = (outMin + balN - (inMax - d.max)).coerceAtLeast(Int.MIN_VALUE.toLong()).toInt()
                if (!state.tightenIntMin(flow[a], minRequired, ant)) return false
            }
            for (a in outArcs[n]) {
                val d = state.intDomains[flow[a]]
                // outMin' contribution: new_min ≤ inMax - balN - (outMin - d.min)  → upper.
                val maxAllowed = (inMax - balN - (outMin - d.min)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (!state.tightenIntMax(flow[a], maxAllowed, ant)) return false
                val minRequired = (inMin - balN - (outMax - d.max)).coerceAtLeast(Int.MIN_VALUE.toLong()).toInt()
                if (!state.tightenIntMin(flow[a], minRequired, ant)) return false
            }
        }
        // Cost equation: cost = Σ weight·flow.
        if (cost >= 0 && weight != null) {
            var sumMin = 0L; var sumMax = 0L
            for (a in flow.indices) {
                val w = weight[a]; val d = state.intDomains[flow[a]]
                if (w >= 0) { sumMin += w.toLong() * d.min; sumMax += w.toLong() * d.max }
                else { sumMin += w.toLong() * d.max; sumMax += w.toLong() * d.min }
            }
            val cdHi = sumMax.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val cdLo = sumMin.coerceAtLeast(Int.MIN_VALUE.toLong()).toInt()
            if (!state.tightenIntMin(cost, cdLo, ant)) return false
            if (!state.tightenIntMax(cost, cdHi, ant)) return false
        }
        return true
    }
}
