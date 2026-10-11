package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.LinearOp
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
class ChannelingSink internal constructor(intVar: Int, newValue: Long) {
    private val parts = ArrayList<Move>(INITIAL_PARTS)
    private val pinned = IntHashSet()
    private var indicatorFlips: IntHashSet? = null

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

    internal fun addIndicatorFlip(boolVar: Int) {
        val indicators = indicatorFlips ?: IntHashSet().also { indicatorFlips = it }
        if (indicators.add(boolVar)) parts += Move.BoolFlip(boolVar)
    }

    internal fun carryBinaryChannels(state: LocalSearchState, eligibility: MoveSink) {
        if (parts.none { it is Move.BoolFlip }) return
        val flipped = IntHashSet()
        for (part in parts) {
            if (part is Move.BoolFlip && !flipped.add(part.varId)) flipped.remove(part.varId)
        }
        val visited = IntHashSet()
        val initialSize = parts.size
        for (i in 0 until initialSize) {
            val part = parts[i] as? Move.BoolFlip ?: continue
            val boolVar = part.varId
            if (boolVar !in flipped || !visited.add(boolVar) || !eligibility.allowsBool(boolVar)) continue
            carryBinaryChannels(state, boolVar, eligibility)
        }
    }

    private fun carryBinaryChannels(state: LocalSearchState, boolVar: Int, eligibility: MoveSink) {
        val desired = !state.assignment.boolValue(boolVar)
        for (fid in state.projection.boolOccurrences[boolVar]) {
            val row = state.problem.factors[fid] as? ReifiedLinear ?: continue
            val value = binaryChannelValue(state, row, desired) ?: continue
            val variable = row.vars[0]
            if (isPinned(variable) || !eligibility.allowsInt(variable)) continue
            if (value == state.assignment.intValue(variable)) continue
            pin(variable)
            parts += Move.IntSet(variable, value)
        }
    }

    private fun binaryChannelValue(state: LocalSearchState, row: ReifiedLinear, desired: Boolean): Long? {
        if (row.op != LinearOp.EQ || row.vars.size != 1) return null
        val constants = row.integerConstants ?: return null
        if (constants.coeff(0) != 1L || constants.bound !in 0L..1L) return null
        val domain = state.rootDomains[row.vars[0]]
        if (domain.min < 0L || domain.max > 1L) return null
        val value = if (desired) constants.bound else 1L - constants.bound
        return if (value in domain) value else null
    }

    /** The synthesized move: the bare driving [Move.IntSet] when no contributor added anything, else a
     *  [Move.Compound] of the driving set plus every contributed part. */
    internal fun toMove(): Move = if (parts.size == 1) parts[0] else Move.Compound(parts)

    private companion object {
        const val INITIAL_PARTS: Int = 4
    }
}
