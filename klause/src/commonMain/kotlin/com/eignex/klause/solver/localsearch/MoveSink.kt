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

    /** Add a multi-variable atomic transition. Skips the move entirely if any part
     *  would touch a frozen variable — a Compound is all-or-nothing. */
    fun addCompound(parts: List<Move>) {
        for (p in parts) when (p) {
            is Move.BoolFlip -> if (assumptions.isFrozenBool(p.varId)) return
            is Move.IntSet -> if (assumptions.isFrozenInt(p.varId)) return
            is Move.Compound -> error("Compound parts must be primitive (BoolFlip/IntSet)")
        }
        moves += Move.Compound(parts)
    }

    fun clear() { moves.clear() }
}
