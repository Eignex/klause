package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `bin_packing` family — item-to-bin assignment with weight totals subject to bin
 * capacities. One factor covers all three MiniZinc variants via [mode]:
 *
 *  - [Mode.UniformCapacity]: single scalar [uniformCapacity]; every bin caps at the same
 *    value. (`fzn_bin_packing(capacity, bins, weights)`)
 *  - [Mode.PerBinCapacity]: per-bin caps in [capacities]. ([fzn_bin_packing_capa])
 *  - [Mode.LoadVars]: per-bin load variables in [loadVars]; the constraint
 *    `load[b] = Σ weights[i] · 1[bins[i] = b + binOffset]`. ([fzn_bin_packing_load])
 *
 * [binOffset] is the value `bins[i]` takes for bin 0 — typically `1` for MZN 1-based.
 */
class BinPacking(
    val bins: IntArray,
    val weights: IntArray,
    val mode: Mode,
    val uniformCapacity: Int = 0,
    val capacities: IntArray? = null,
    val loadVars: IntArray? = null,
    val numBins: Int,
    val binOffset: Int = 1,
) : LocalSearchFactor {

    enum class Mode { UniformCapacity, PerBinCapacity, LoadVars }

    init {
        require(bins.size == weights.size) { "bin_packing: bins/weights size mismatch" }
        require(numBins >= 1) { "bin_packing: numBins ≥ 1" }
        when (mode) {
            Mode.UniformCapacity -> { /* uniformCapacity used */ }
            Mode.PerBinCapacity -> require(capacities != null && capacities.size == numBins) {
                "bin_packing_capa: capacities[$numBins] required"
            }
            Mode.LoadVars -> require(loadVars != null && loadVars.size == numBins) {
                "bin_packing_load: loadVars[$numBins] required"
            }
        }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = run {
        val lv = loadVars
        if (lv != null) bins + lv else bins
    }

    /** Per-bin current load under the assignment. */
    private class State(val loads: IntArray)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val loads = IntArray(numBins)
        for (i in bins.indices) {
            val b = state.assignment.intValue(bins[i]) - binOffset
            if (b in 0 until numBins) loads[b] += weights[i]
        }
        state.refPayload[factorId] = State(loads)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return when (mode) {
            Mode.UniformCapacity -> s.loads.any { it > uniformCapacity }
            Mode.PerBinCapacity -> s.loads.indices.any { s.loads[it] > capacities!![it] }
            Mode.LoadVars -> s.loads.indices.any {
                state.assignment.intValue(loadVars!![it]) != s.loads[it]
            }
        }
    }

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val wasViolated = isViolated(state, factorId)
        // Simulate the change on a loads copy.
        val sim = s.loads.copyOf()
        for (i in bins.indices) {
            if (bins[i] != intVar) continue
            val oldBin = state.assignment.intValue(intVar) - binOffset
            val newBin = newValue - binOffset
            if (oldBin in 0 until numBins) sim[oldBin] -= weights[i]
            if (newBin in 0 until numBins) sim[newBin] += weights[i]
        }
        val willViolate = when (mode) {
            Mode.UniformCapacity -> sim.any { it > uniformCapacity }
            Mode.PerBinCapacity -> sim.indices.any { sim[it] > capacities!![it] }
            Mode.LoadVars -> sim.indices.any {
                val lv = loadVars!![it]
                val v = if (lv == intVar) newValue else state.assignment.intValue(lv)
                v != sim[it]
            }
        }
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        val wasViolated = run {
            // Reverse the change on a copy to compute pre-update violation.
            val sim = s.loads.copyOf()
            for (i in bins.indices) {
                if (bins[i] != intVar) continue
                val oldBin = oldValue - binOffset
                val curBin = cur - binOffset
                if (curBin in 0 until numBins) sim[curBin] -= weights[i]
                if (oldBin in 0 until numBins) sim[oldBin] += weights[i]
            }
            when (mode) {
                Mode.UniformCapacity -> sim.any { it > uniformCapacity }
                Mode.PerBinCapacity -> sim.indices.any { sim[it] > capacities!![it] }
                Mode.LoadVars -> sim.indices.any {
                    val lv = loadVars!![it]
                    val v = if (lv == intVar) oldValue else state.assignment.intValue(lv)
                    v != sim[it]
                }
            }
        }
        for (i in bins.indices) {
            if (bins[i] != intVar) continue
            val oldBin = oldValue - binOffset
            val newBin = cur - binOffset
            if (oldBin in 0 until numBins) s.loads[oldBin] -= weights[i]
            if (newBin in 0 until numBins) s.loads[newBin] += weights[i]
        }
        val nowViolated = isViolated(state, factorId)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        // Tighten each bins[i] to [binOffset, binOffset + numBins - 1].
        for (b in bins) {
            if (!state.tightenIntMin(b, binOffset)) return false
            if (!state.tightenIntMax(b, binOffset + numBins - 1)) return false
        }
        // Per-bin "definite" load = sum of weights of items whose bin var is singleton on
        // this bin; "possible" load = sum of weights of items whose bin var still contains
        // this bin. Drives both capacity-mode failure and load-var bound tightening.
        val definiteLoads = IntArray(numBins)
        val possibleLoads = IntArray(numBins)
        for (i in bins.indices) {
            val d = state.intDomains[bins[i]]
            for (k in 0 until numBins) {
                val binValue = k + binOffset
                if (d.min == d.max && d.min == binValue) definiteLoads[k] += weights[i]
                if (binValue in d) possibleLoads[k] += weights[i]
            }
        }
        when (mode) {
            Mode.UniformCapacity -> for (k in 0 until numBins) {
                if (definiteLoads[k] > uniformCapacity) return false
            }
            Mode.PerBinCapacity -> for (k in 0 until numBins) {
                if (definiteLoads[k] > capacities!![k]) return false
            }
            Mode.LoadVars -> {
                val ant = state.composeIntVarAntecedents(bins)
                for (k in 0 until numBins) {
                    if (!state.tightenIntMin(loadVars!![k], definiteLoads[k], ant)) return false
                    if (!state.tightenIntMax(loadVars[k], possibleLoads[k], ant)) return false
                }
            }
        }
        return true
    }
}
