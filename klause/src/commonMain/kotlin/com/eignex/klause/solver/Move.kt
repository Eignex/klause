package com.eignex.klause.solver

/**
 * A primitive change applied to a single variable. Strategies enumerate moves, score each
 * via factor delta methods, then commit one through the LS engine's `apply`.
 */
sealed interface Move {
    /** Flip a Boolean variable's current value. */
    data class BoolFlip(val varId: Int) : Move

    /** Set an integer variable to [newValue]. */
    data class IntSet(val varId: Int, val newValue: Int) : Move
}
