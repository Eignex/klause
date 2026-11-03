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
 * of one [Move.Compound]. Coordinate claims guard against two sibling factors proposing conflicting shifts
 * of the same int variable; the first to claim it wins.
 */
class ChannelingSink internal constructor(intVar: Int, newValue: Long) {
    private val parts = ArrayList<Move>(INITIAL_PARTS)
    private val drivingIntVar = intVar
    private var additionalPins: IntHashSet? = null
    private var indicatorFlips: IntHashSet? = null
    private var firstIndicator: Int = 0
    private var secondIndicator: Int = 0
    private var hasIndicator: Boolean = false
    private var hasSecondIndicator: Boolean = false

    init {
        parts += Move.IntSet(intVar, newValue)
    }

    /** True iff [intVar] is already claimed by the driving move or an earlier contribution, so a
     *  contributor must not propose another change to it. */
    fun isPinned(intVar: Int): Boolean = intVar == drivingIntVar || additionalPins?.contains(intVar) == true

    /** Claim [intVar] so no later contributor shifts it again. */
    fun pin(intVar: Int) {
        if (intVar == drivingIntVar) return
        val pins = additionalPins ?: IntHashSet().also { additionalPins = it }
        pins.add(intVar)
    }

    /** Append [move] as a part of the synthesized compound. */
    fun add(move: Move) {
        parts += move
    }

    internal fun addIndicatorFlip(boolVar: Int) {
        if (!hasIndicator) {
            firstIndicator = boolVar
            hasIndicator = true
            parts += Move.BoolFlip(boolVar)
            return
        }
        if (boolVar == firstIndicator) return
        if (!hasSecondIndicator) {
            secondIndicator = boolVar
            hasSecondIndicator = true
            parts += Move.BoolFlip(boolVar)
            return
        }
        if (boolVar == secondIndicator) return
        val indicators = indicatorFlips ?: IntHashSet().also {
            it.add(firstIndicator)
            it.add(secondIndicator)
            indicatorFlips = it
        }
        if (indicators.add(boolVar)) parts += Move.BoolFlip(boolVar)
    }

    internal fun carryBinaryChannels(state: LocalSearchState, eligibility: MoveSink) {
        var firstFlip: Move.BoolFlip? = null
        var secondFlip: Move.BoolFlip? = null
        var moreThanTwoFlips = false
        for (part in parts) {
            if (part !is Move.BoolFlip) continue
            if (firstFlip == null) {
                firstFlip = part
            } else if (secondFlip == null) {
                secondFlip = part
            } else {
                moreThanTwoFlips = true
                break
            }
        }
        val first = firstFlip ?: return
        if (!moreThanTwoFlips) {
            val second = secondFlip
            if (second != null && second.varId == first.varId) return
            if (eligibility.allowsBool(first.varId)) carryBinaryChannels(state, first.varId, eligibility)
            if (second != null && eligibility.allowsBool(second.varId)) {
                carryBinaryChannels(state, second.varId, eligibility)
            }
            return
        }
        carryBinaryChannelParity(state, eligibility)
    }

    private fun carryBinaryChannelParity(state: LocalSearchState, eligibility: MoveSink) {
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
