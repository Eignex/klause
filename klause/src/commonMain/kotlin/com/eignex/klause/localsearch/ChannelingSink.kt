package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.Move
import com.eignex.klause.util.IntHashSet

/**
 * Accumulator that [Invariant.contributeChanneling] appends to while [LocalSearchState] synthesizes a
 * value-driven channeling move. Seeded with the driving `IntSet(intVar, newValue)` (already pinned),
 * it collects the coordinated sibling updates — indicator flips, sum counter-shifts — into the parts
 * of one [Move.Compound]. The pin set guards against two sibling factors proposing conflicting shifts
 * of the same int variable; the first to claim it wins.
 */
class ChannelingSink internal constructor(intVar: Int, newValue: Int) {
    private val parts = ArrayList<Move>(INITIAL_PARTS)
    private val pinned = IntHashSet()

    init {
        pinned.add(intVar)
        parts += Move.IntSet(intVar, newValue)
    }

    /** True iff [intVar] is already claimed by the driving move or an earlier contribution, so a
     *  contributor must not propose another change to it. */
    fun isPinned(intVar: Int): Boolean = intVar in pinned

    /** Claim [intVar] so no later contributor shifts it again. */
    fun pin(intVar: Int) {
        pinned.add(intVar)
    }

    /** Append [move] as a part of the synthesized compound. */
    fun add(move: Move) {
        parts += move
    }

    /** The synthesized move: the bare driving [Move.IntSet] when no contributor added anything, else a
     *  [Move.Compound] of the driving set plus every contributed part. */
    internal fun toMove(): Move = if (parts.size == 1) parts[0] else Move.Compound(parts)

    private companion object {
        const val INITIAL_PARTS: Int = 4
    }
}
