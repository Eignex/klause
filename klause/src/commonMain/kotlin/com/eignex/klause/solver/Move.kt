package com.eignex.klause.solver

/**
 * A primitive change applied to a single variable. Strategies enumerate moves, score each via
 * factor delta methods, then commit one through [SolverState.apply].
 */
sealed interface Move {
    /** Flip a Boolean variable's current value. */
    data class BoolFlip(val varId: Int) : Move

    /** Set an integer variable to [newValue]. */
    data class IntSet(val varId: Int, val newValue: Int) : Move
}

/** Mutable accumulator factors push repair-move suggestions into. Optionally consults
 *  an [Assumptions] set so a frozen variable never enters the candidate list. */
class MoveSink(private var assumptions: Assumptions = Assumptions.None) {
    private val moves: MutableList<Move> = ArrayList()
    val list: List<Move> get() = moves

    /** Replace the [Assumptions] this sink filters against. Called by [SolverState] on
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
