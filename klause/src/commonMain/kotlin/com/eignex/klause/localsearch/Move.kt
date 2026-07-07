package com.eignex.klause.localsearch

/**
 * A primitive change applied to a single variable. Strategies enumerate moves, score each
 * via factor delta methods, then commit one through the LS engine's `apply`.
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

    /**
     * Two or more single-variable moves applied as one transition. The engine commits
     * each part in `parts` order, but break score / net delta / tabu are evaluated
     * against the full post-state — so a Compound that resolves a conflict via two
     * coupled changes can score better than either part alone.
     *
     * Parts must be `BoolFlip` or `IntSet` (no Compound-of-Compound); the LS engine
     * uses apply-then-revert to evaluate cost diffs, which only works for invertible
     * primitives. Constructed via [com.eignex.klause.localsearch.MoveSink.addCompound];
     * a Compound is tabu if *any* part is tabu (conservative).
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
