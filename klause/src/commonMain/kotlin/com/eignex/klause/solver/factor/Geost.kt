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
 * Propagation in this first cut:
 *  - For each pair (i, j): check if in EVERY dimension the boxes must overlap (`origin[i,d] +
 *    size[i,d] > origin[j,d].max` AND `origin[j,d] + size[j,d] > origin[i,d].max`, both sides).
 *    If so, fail.
 *  - If in exactly one dimension a non-overlap is still feasible, propagate that dimension's
 *    inequality (one of the two LE constraints) to bound consistency.
 *
 * This beats the AST-level pairwise Or-of-LE decomposition by recognising the "forced single
 * dimension" case in a single pass rather than relying on the OR-clause structure for
 * cross-dim reasoning.
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

    /** Returns one of {Forced.LeftLow, Forced.RightLow, Free.MaybeEither, Forced.Conflict}. */
    private enum class PairDim { LEFT_LOW_FORCED, RIGHT_LOW_FORCED, MAYBE_EITHER, OVERLAP_FORCED }

    private fun pairDimRel(state: PropagationState, i: Int, j: Int, d: Int): PairDim {
        val oi = origin[i * numDims + d]
        val oj = origin[j * numDims + d]
        val si = length[i * numDims + d]
        val sj = length[j * numDims + d]
        val di = state.intDomains[oi]
        val dj = state.intDomains[oj]
        // i fully left of j  ⟺  origin_i + si ≤ origin_j  ⟺  origin_i ≤ origin_j − si.
        val leftPossible = di.min + si <= dj.max // there exists assignment with i + si ≤ j
        val rightPossible = dj.min + sj <= di.max
        return when {
            !leftPossible && !rightPossible -> PairDim.OVERLAP_FORCED
            leftPossible && !rightPossible -> PairDim.LEFT_LOW_FORCED
            !leftPossible && rightPossible -> PairDim.RIGHT_LOW_FORCED
            else -> PairDim.MAYBE_EITHER
        }
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val ant = state.composeIntVarAtomAntecedents(intVars)
        for (i in 0 until numObjects) for (j in i + 1 until numObjects) {
            // Determine feasibility in each dimension.
            var freeDims = 0
            var forcedDim = -1
            var forcedSide = 0  // 1 = i left, -1 = j left
            var allOverlap = true
            for (d in 0 until numDims) {
                val rel = pairDimRel(state, i, j, d)
                if (rel == PairDim.OVERLAP_FORCED) {
                    // this axis cannot separate; allOverlap remains true
                } else {
                    allOverlap = false
                    if (rel == PairDim.LEFT_LOW_FORCED) { forcedDim = d; forcedSide = 1 }
                    else if (rel == PairDim.RIGHT_LOW_FORCED) { forcedDim = d; forcedSide = -1 }
                    else freeDims++
                }
            }
            if (allOverlap) return false
            // If exactly one dim is feasible for separation and it's forced one-way,
            // tighten that axis. If multiple free dims, defer to OR-clause propagation.
            if (freeDims == 0 && forcedDim >= 0) {
                val oi = origin[i * numDims + forcedDim]
                val oj = origin[j * numDims + forcedDim]
                val si = length[i * numDims + forcedDim]
                val sj = length[j * numDims + forcedDim]
                if (forcedSide == 1) {
                    // origin_i + si ≤ origin_j.
                    val newMax = state.intDomains[oj].max - si
                    if (!state.tightenIntMax(oi, newMax, ant)) return false
                    val newMin = state.intDomains[oi].min + si
                    if (!state.tightenIntMin(oj, newMin, ant)) return false
                } else {
                    val newMax = state.intDomains[oi].max - sj
                    if (!state.tightenIntMax(oj, newMax, ant)) return false
                    val newMin = state.intDomains[oj].min + sj
                    if (!state.tightenIntMin(oi, newMin, ant)) return false
                }
            }
        }
        return true
    }
}
