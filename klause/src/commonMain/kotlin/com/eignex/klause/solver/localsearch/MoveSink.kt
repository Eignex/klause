package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Move

/**
 * Mutable accumulator that factors push repair-move suggestions into. Optionally consults
 * an [Assumptions] set so a frozen variable never enters the candidate list. LS-only — the
 * propagation contract doesn't use it.
 */
class MoveSink(private var assumptions: Assumptions = Assumptions.None) {
    private val moves: MutableList<Move> = ArrayList()
    val list: List<Move> get() = moves

    /** Replace the [Assumptions] this sink filters against. Called by [LocalSearchState] on
     *  init / restart so per-call assumptions take effect. */
    fun setAssumptions(a: Assumptions) { assumptions = a }

    fun addBoolFlip(varId: Int) {
        if (assumptions.isFrozenBool(varId)) return
        moves += Move.BoolFlip(varId)
    }
    fun addIntSet(varId: Int, newValue: Int) {
        if (assumptions.isFrozenInt(varId)) return
        moves += Move.IntSet(varId, newValue)
    }
    fun clear() { moves.clear() }
}
