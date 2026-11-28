package com.eignex.klause.localsearch

/**
 * A change to the assignment: a primitive single-variable move, or a [Compound] of them. Strategies
 * enumerate moves, score each via factor delta methods, then commit one through [LocalSearchState.apply].
 */
sealed interface Move {
    /** Flip a Boolean variable's current value. */
    data class BoolFlip(
        /** Boolean variable id to flip. */
        val varId: Int,
    ) : Move

    /** Set an integer variable to [newValue]. */
    data class IntSet(
        /** Integer variable id to set. */
        val varId: Int,
        /** New value to assign. */
        val newValue: Long,
    ) : Move

    /** Set a continuous variable to [newValue]. */
    data class RealSet(
        /** Real variable id to set. */
        val varId: Int,
        /** New value to assign. */
        val newValue: Double,
    ) : Move

    /**
     * Two or more primitive moves applied in order as one transition, scored against the full post-state so
     * coupled changes can beat either part alone. Parts must be primitive because scoring applies and reverts
     * them; a compound is tabu when any part is.
     */
    data class Compound(
        /** The single-variable moves applied together, in order. */
        val parts: List<Move>,
    ) : Move {
        init {
            require(parts.size >= 2) { "Compound move needs at least 2 parts, got ${parts.size}" }
            require(parts.all { it !is Compound }) { "Compound move cannot nest another Compound" }
        }
    }
}
