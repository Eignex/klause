package com.eignex.klause.factor.circuit

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.IntIntMap

/** Shared base for [CircuitInvariant] and [SubcircuitInvariant]: violation scoring over a successor
 *  array driven by a caller-supplied cost function. */
internal open class SuccessorCycleInvariant(
    protected val succ: IntArray,
    protected val n: Int,
    protected val computeCost: (LocalSearchState, Int, Int) -> Int,
) : Invariant {

    protected val positionOfVar: IntIntMap = IntIntMap.build(succ, IntArray(n) { it }, absent = -1)

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.intPayload[factorId] = computeCost(state, -1, 0)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = state.intPayload[factorId] > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = state.intPayload[factorId]

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val pos = positionOfVar[intVar]
        if (pos < 0) return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, pos, newValue)
        return newCost - oldCost
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        if (positionOfVar[intVar] < 0) return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, -1, 0)
        state.intPayload[factorId] = newCost
        return newCost - oldCost
    }

    /**
     * 2-opt segment reversals over a cycle given as [order] (node ids in visiting order, closed
     * cyclically). Reverses a bounded interior segment — removing two edges and reconnecting their
     * endpoints with the segment between them flipped — and emits each as one atomic [Move.Compound]
     * over the affected successor variables. Feasibility-preserving (reversing a segment of a valid
     * cycle yields a valid cycle), so callers use it for structured moves; [CircuitInvariant] passes
     * the whole tour and [SubcircuitInvariant] the active sub-tour.
     */
    protected fun proposeReversals(state: LocalSearchState, order: IntArray, sink: MoveSink, cap: Int, stride: Int) {
        val len = order.size
        if (len < 4) return
        var emitted = 0
        var attempts = 0
        while (emitted < cap && attempts < cap * stride) {
            attempts++
            val i = state.rng.nextInt(len - 1)
            val maxSeg = minOf(MAX_REVERSAL_SEGMENT, len - 1 - i)
            if (maxSeg < 2) continue
            val j = i + 2 + state.rng.nextInt(maxSeg - 1)
            val parts = reversalCompound(state, order, i, j) ?: continue
            sink.addCompound(parts)
            emitted++
        }
    }

    /** Successor moves reversing cycle segment `(i, j]`: `order(i) -> order(j)`, the reversed interior
     *  `order(k) -> order(k-1)`, and `order(i+1) -> order(j+1)`. Null if any new edge is frozen or out
     *  of its successor variable's domain. */
    private fun reversalCompound(state: LocalSearchState, order: IntArray, i: Int, j: Int): List<Move>? {
        val len = order.size
        val parts = ArrayList<Move>(j - i + 1)
        if (!appendEdge(state, order[i], order[j], parts)) return null
        var k = j
        while (k >= i + 2) {
            if (!appendEdge(state, order[k], order[k - 1], parts)) return null
            k--
        }
        if (!appendEdge(state, order[i + 1], order[(j + 1) % len], parts)) return null
        return parts
    }

    private fun appendEdge(state: LocalSearchState, from: Int, to: Int, parts: ArrayList<Move>): Boolean {
        val v = succ[from]
        if (state.assumptions.isFrozenInt(v)) return false
        if (to !in state.problem.intDomains[v]) return false
        parts.add(Move.IntSet(v, to))
        return true
    }

    /** Shared reversal bound. */
    protected companion object {
        /** Longest cycle segment a single 2-opt reversal flips; bounds the compound's size so the
         *  reversal stays a cheap local move rather than a near-global rewrite. */
        const val MAX_REVERSAL_SEGMENT: Int = 12
    }
}
