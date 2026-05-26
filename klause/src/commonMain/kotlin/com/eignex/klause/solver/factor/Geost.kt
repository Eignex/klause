package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `geost` — N-dimensional non-overlapping placement of axis-aligned boxes. [origin] is a
 * row-major `[numObjects × numDims]` integer-variable array; [length] is the matching
 * constant size table.
 *
 * Propagation runs a full sweep-line per (target object i, target dimension d). For each
 * other object j we test whether, on EVERY dimension d' ≠ d, the pair (i, j) must overlap
 * regardless of their assigned origins in d' — that is, origin_i.d' is currently confined
 * to the *mandatory-overlap interval* `[j.max + 1 − s_i.d', j.min + s_j.d' − 1]`. If yes
 * for all d' ≠ d, then placing origin_i.d in `[j.max + 1 − s_i.d, j.min + s_j.d − 1]` would
 * force an overlap on d too — a global conflict. We collect those forbidden intervals,
 * union them, and tighten origin_i.d to avoid the union (advance min past leading intervals,
 * retract max past trailing intervals; report failure if min > max).
 *
 * The earlier "forced single-dim" pairwise scan is subsumed by the case where all but one
 * dim's M-intervals are already exhausted.
 */
class Geost(
    val numDims: Int,
    val numObjects: Int,
    val origin: IntArray,
    val length: IntArray,
) : LocalSearchFactor {

    init {
        require(numDims >= 1) { "Geost: numDims must be ≥ 1" }
        require(origin.size == numObjects * numDims) { "Geost: origin shape mismatch" }
        require(length.size == numObjects * numDims) { "Geost: length shape mismatch" }
    }

    override val boolVars: IntArray = EmptyIntArray
    override val intVars: IntArray = origin

    override fun initialize(state: LocalSearchState, factorId: Int) {}
    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = false
    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int = 0
    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int = 0

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.composeIntVarAtomAntecedents(intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val ant = state.composeIntVarAtomAntecedents(intVars)
        // First sweep: detect "must overlap in every dim" → infeasibility.
        for (i in 0 until numObjects) for (j in i + 1 until numObjects) {
            if (mustOverlapEveryDim(state, i, j, exceptDim = -1)) return false
        }
        // Per (i, d) sweep.
        for (i in 0 until numObjects) {
            for (d in 0 until numDims) {
                val oi = origin[i * numDims + d]
                val si = length[i * numDims + d]
                // Collect forbidden intervals for origin_i.d.
                val intervals = collectForbidden(state, i, d, si)
                if (intervals.isEmpty()) continue
                // Sweep min upward.
                var lo = state.intDomains[oi].min
                var hi = state.intDomains[oi].max
                var changed = true
                while (changed) {
                    changed = false
                    for (k in intervals.indices step 2) {
                        val fLo = intervals[k]; val fHi = intervals[k + 1]
                        if (lo in fLo..fHi) { lo = fHi + 1; changed = true }
                        if (hi in fLo..fHi) { hi = fLo - 1; changed = true }
                    }
                }
                if (lo > hi) return false
                if (!state.tightenIntMin(oi, lo, ant)) return false
                if (!state.tightenIntMax(oi, hi, ant)) return false
            }
        }
        return true
    }

    /**
     * Return the union of forbidden intervals for origin_i.d, as a flat list of
     * [lo0, hi0, lo1, hi1, …] (inclusive bounds). An interval comes from some j ≠ i
     * for which on every dim d' ≠ d the pair already must overlap.
     */
    private fun collectForbidden(state: PropagationState, i: Int, d: Int, si: Int): IntArray {
        val acc = IntArray(numObjects * 2)
        var n = 0
        for (j in 0 until numObjects) {
            if (j == i) continue
            if (!mustOverlapAllOtherDims(state, i, j, d)) continue
            val oj = origin[j * numDims + d]
            val sj = length[j * numDims + d]
            val dj = state.intDomains[oj]
            val flo = dj.max + 1 - si
            val fhi = dj.min + sj - 1
            if (flo <= fhi) {
                acc[n] = flo; acc[n + 1] = fhi; n += 2
            }
        }
        return acc.copyOf(n)
    }

    /** True iff for every dim d' ≠ [exceptDim] (or every dim if -1), the pair (i, j) must
     *  overlap regardless of origin_i.d' and origin_j.d' values within current bounds. */
    private fun mustOverlapAllOtherDims(state: PropagationState, i: Int, j: Int, exceptDim: Int): Boolean {
        for (dp in 0 until numDims) {
            if (dp == exceptDim) continue
            if (!mustOverlapDim(state, i, j, dp)) return false
        }
        return true
    }

    private fun mustOverlapEveryDim(state: PropagationState, i: Int, j: Int, exceptDim: Int): Boolean =
        mustOverlapAllOtherDims(state, i, j, exceptDim)

    /** True iff origin_i.d ⊆ [origin_j.d.max + 1 − s_i.d, origin_j.d.min + s_j.d − 1]. */
    private fun mustOverlapDim(state: PropagationState, i: Int, j: Int, d: Int): Boolean {
        val oi = origin[i * numDims + d]
        val oj = origin[j * numDims + d]
        val si = length[i * numDims + d]
        val sj = length[j * numDims + d]
        val di = state.intDomains[oi]
        val dj = state.intDomains[oj]
        val mLo = dj.max + 1 - si
        val mHi = dj.min + sj - 1
        return mLo <= mHi && di.min >= mLo && di.max <= mHi
    }
}
