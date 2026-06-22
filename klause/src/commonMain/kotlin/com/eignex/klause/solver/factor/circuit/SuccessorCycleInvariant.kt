package com.eignex.klause.solver.factor.circuit

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.localsearch.LocalSearchState
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
}
