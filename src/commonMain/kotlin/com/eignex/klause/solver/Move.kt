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

/** Mutable accumulator factors push repair-move suggestions into. */
class MoveSink {
    private val moves: MutableList<Move> = ArrayList()
    val list: List<Move> get() = moves

    fun addBoolFlip(varId: Int) { moves += Move.BoolFlip(varId) }
    fun addIntSet(varId: Int, newValue: Int) { moves += Move.IntSet(varId, newValue) }
    fun clear() { moves.clear() }
}
