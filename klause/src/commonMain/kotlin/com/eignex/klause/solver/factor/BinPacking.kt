package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `bin_packing` family — item-to-bin assignment with weight totals subject to bin
 * capacities. One factor covers all three MiniZinc variants via [mode]:
 *
 *  - [Mode.UniformCapacity]: single scalar [uniformCapacity]; every bin caps at the same
 *    value. (`fzn_bin_packing(capacity, bins, weights)`)
 *  - [Mode.PerBinCapacity]: per-bin caps in [capacities]. ([fzn_bin_packing_capa])
 *  - [Mode.LoadVars]: per-bin load variables in [loadVars]; the constraint
 *    `load`b` = Σ weights[i] · 1[bins[i] = b + binOffset]`. ([fzn_bin_packing_load])
 *
 * [binOffset] is the value `bins[i]` takes for bin 0 — typically `1` for MZN 1-based.
 */
class BinPacking(
    /** Bin-assignment variable id per item. */
    val bins: IntArray,
    /** Item weights, parallel to [bins]. */
    val weights: IntArray,
    /** Which capacity/load variant this factor enforces. */
    val mode: Mode,
    /** Shared capacity for [Mode.UniformCapacity]. */
    val uniformCapacity: Int = 0,
    /** Per-bin capacities for [Mode.PerBinCapacity]. */
    val capacities: IntArray? = null,
    /** Per-bin load variable ids for [Mode.LoadVars]. */
    val loadVars: IntArray? = null,
    /** Number of bins. */
    val numBins: Int,
    /** Value `bins[i]` takes for bin 0 (typically 1 for 1-based MiniZinc). */
    val binOffset: Int = 1,
) : LocalSearchFactor {

    /** Which bin-packing capacity/load variant a [BinPacking] enforces. */
    enum class Mode {
        /** Single shared capacity ([uniformCapacity]) for every bin. */
        UniformCapacity,

        /** Per-bin capacities ([capacities]). */
        PerBinCapacity,

        /** Per-bin load variables ([loadVars]). */
        LoadVars,
    }

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

            Mode.PerBinCapacity -> s.loads.indices.any { s.loads[it] > requireNotNull(capacities)[it] }

            Mode.LoadVars -> s.loads.indices.any {
                state.assignment.intValue(requireNotNull(loadVars)[it]) != s.loads[it]
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

            Mode.PerBinCapacity -> sim.indices.any { sim[it] > requireNotNull(capacities)[it] }

            Mode.LoadVars -> sim.indices.any {
                val lv = requireNotNull(loadVars)[it]
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

                Mode.PerBinCapacity -> sim.indices.any { sim[it] > requireNotNull(capacities)[it] }

                Mode.LoadVars -> sim.indices.any {
                    val lv = requireNotNull(loadVars)[it]
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
                if (definiteLoads[k] > requireNotNull(capacities)[k]) return false
            }

            Mode.LoadVars -> {
                val ant = state.composeIntVarAtomAntecedents(bins)
                for (k in 0 until numBins) {
                    if (!state.tightenIntMin(requireNotNull(loadVars)[k], definiteLoads[k], ant)) return false
                    if (!state.tightenIntMax(loadVars[k], possibleLoads[k], ant)) return false
                }
            }
        }
        return true
    }

    /**
     * Repair: for each overloaded bin, propose a rich candidate set rather than a single
     * "heaviest to most-slack" move. The LS scorer picks among them. Specifically:
     *  1. Move each of the top-K heaviest items in the overloaded bin to its best-fit
     *     receiver — gives multiple alternatives when the absolute-heaviest doesn't fit.
     *  2. For each item in the overloaded bin, propose every receiver bin whose slack
     *     would accept it (capped per item).
     *  3. Pair swaps: for items currently in the overloaded bin, find an item in a less
     *     loaded bin whose weight roughly compensates and propose the atomic swap.
     *  Under [Mode.LoadVars] also snap loadVars to current loads.
     */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as State
        if (mode == Mode.LoadVars) {
            for (k in 0 until numBins) {
                val lv = requireNotNull(loadVars)[k]
                val cur = state.assignment.intValue(lv)
                if (cur != s.loads[k] && s.loads[k] in state.problem.intDomains[lv]) {
                    sink.addChannelingIntSet(state, lv, s.loads[k])
                }
            }
        }
        // Per-bin capacities (called repeatedly below).
        fun capOf(k: Int): Int = when (mode) {
            Mode.UniformCapacity -> uniformCapacity
            Mode.PerBinCapacity -> requireNotNull(capacities)[k]
            Mode.LoadVars -> Int.MAX_VALUE
        }
        for (b in 0 until numBins) {
            val cap = capOf(b)
            if (s.loads[b] <= cap) continue
            // Collect every item currently assigned to overloaded bin b, sorted by
            // weight descending — the top of the list is the highest-leverage candidate
            // but lighter items may fit when the heaviest doesn't.
            val itemsHere = ArrayList<Int>()
            for (i in bins.indices) {
                val itemBin = state.assignment.intValue(bins[i]) - binOffset
                if (itemBin == b) itemsHere.add(i)
            }
            if (itemsHere.isEmpty()) continue
            itemsHere.sortByDescending { weights[it] }

            // (1) + (2): for each of the top-K heaviest items propose moves to receivers.
            val topK = minOf(MAX_ITEMS_PER_BIN, itemsHere.size)
            for (idxInList in 0 until topK) {
                val itemI = itemsHere[idxInList]
                val itemBinVar = bins[itemI]
                val itemDom = state.problem.intDomains[itemBinVar]
                var receiversAdded = 0
                for (k in 0 until numBins) {
                    if (k == b) continue
                    val slack = capOf(k) - s.loads[k]
                    if (slack < weights[itemI]) continue
                    val target = k + binOffset
                    if (target !in itemDom) continue
                    sink.addChannelingIntSet(state, itemBinVar, target)
                    if (++receiversAdded >= MAX_RECEIVERS_PER_ITEM) break
                }
            }

            // (3): swap pairs. For each top-K heaviest item in b, look for an item in
            // another bin whose weight is close enough that the swap (a) frees enough
            // slack in b and (b) doesn't overload the receiver. Bounded by item count.
            for (idxInList in 0 until topK) {
                val itemI = itemsHere[idxInList]
                val wI = weights[itemI]
                val binVarI = bins[itemI]
                val domI = state.problem.intDomains[binVarI]
                var swapsAdded = 0
                for (j in bins.indices) {
                    if (j == itemI) continue
                    val jBin = state.assignment.intValue(bins[j]) - binOffset
                    if (jBin == b) continue // both in overloaded bin — skip
                    if (jBin < 0 || jBin >= numBins) continue
                    val wJ = weights[j]
                    // Net effect on b: -wI + wJ. Want ≤ 0 (i.e. wJ ≤ wI) so we reduce
                    // load. Net on jBin: -wJ + wI. Want ≤ capOf(jBin) − (s.loads[jBin]).
                    if (wJ > wI) continue
                    val jSlack = capOf(jBin) - s.loads[jBin]
                    if (wI - wJ > jSlack) continue
                    val targetForI = jBin + binOffset
                    val targetForJ = b + binOffset
                    if (targetForI !in domI) continue
                    if (targetForJ !in state.problem.intDomains[bins[j]]) continue
                    sink.addCompound(
                        listOf(
                            IntSet(binVarI, targetForI),
                            IntSet(bins[j], targetForJ),
                        ),
                    )
                    if (++swapsAdded >= MAX_SWAPS_PER_ITEM) break
                }
            }
        }
    }

    private companion object {
        /** Top-K heaviest items in each overloaded bin to consider as movers / swap
         *  pivots. Bounds per-step proposal count at O(numOverloadedBins · K). */
        const val MAX_ITEMS_PER_BIN: Int = 4

        /** Cap on receiver bins proposed per item — diversifies the candidate set without
         *  blowing it up on problems with many bins. */
        const val MAX_RECEIVERS_PER_ITEM: Int = 3

        /** Cap on swap partners proposed per moving item — bounds the inner-loop cost on
         *  problems with many items. */
        const val MAX_SWAPS_PER_ITEM: Int = 2
    }
}
