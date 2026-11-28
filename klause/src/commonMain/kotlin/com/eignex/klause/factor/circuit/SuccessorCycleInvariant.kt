package com.eignex.klause.factor.circuit

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.util.collections.IntIntMap

internal open class SuccessorCycleInvariant(
    protected val succ: IntArray,
    protected val n: Int,
    protected val computeCost: (LocalSearchState, Int, Long) -> Int,
) : Invariant {

    protected val positionOfVar: IntIntMap = IntIntMap.build(succ, IntArray(n) { it }, absent = -1)

    protected fun seedFullCycle(state: LocalSearchState): Boolean {
        for (i in 0 until n) {
            val target = (i + 1) % n
            if (target.toLong() !in state.rootDomains[succ[i]]) return false
            if (state.assumptions.isFrozenInt(succ[i]) && state.assignment.intValue(succ[i]) != target.toLong()) {
                return false
            }
        }
        for (i in 0 until n) {
            if (!state.assumptions.isFrozenInt(succ[i])) state.assignment.setInt(succ[i], ((i + 1) % n).toLong())
        }
        return true
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.intPayload[factorId] = computeCost(state, -1, 0L)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = state.intPayload[factorId] > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = state.intPayload[factorId]

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val pos = positionOfVar[intVar]
        if (pos < 0) return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, pos, newValue)
        return newCost - oldCost
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        if (positionOfVar[intVar] < 0) return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, -1, 0L)
        state.intPayload[factorId] = newCost
        return newCost - oldCost
    }

    // 2-opt segment reversals over a cycle given in visiting order, each one atomic compound move.
    // Reversing a segment of a valid cycle yields a valid cycle. Croes, "A method for solving
    // traveling-salesman problems", Operations Research 6(6), 1958, https://doi.org/10.1287/opre.6.6.791
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

    // Reverses segment (i, j]: order(i) → order(j), order(k) → order(k-1) inside, order(i+1) → order(j+1).
    // Null if any new edge is frozen or outside its successor's domain.
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
        if (to.toLong() !in state.rootDomains[v]) return false
        parts.add(Move.IntSet(v, to.toLong()))
        return true
    }

    protected companion object {
        // Keeps a reversal a cheap local move rather than a near-global rewrite.
        const val MAX_REVERSAL_SEGMENT: Int = 12
    }
}
