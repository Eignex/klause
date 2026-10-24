package com.eignex.klause.factor.global

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.randomValue
import com.eignex.klause.ir.values
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.WIDE_DOMAIN_DRAWS
import com.eignex.klause.localsearch.movesource.PairSwap
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus

internal class AffineDistinctInvariant(private val vars: IntArray, private val offsets: List<BigInt>) : Invariant {
    private val positions = vars.indices.groupBy { vars[it] }
    private val sources = positions.keys.toIntArray()

    private class Counts {
        val values = HashMap<BigInt, Int>()
        var excess = 0

        fun change(value: BigInt, delta: Int): Int {
            val before = values[value] ?: 0
            val after = before + delta
            if (after == 0) values.remove(value) else values[value] = after
            return maxOf(0, after - 1) - maxOf(0, before - 1)
        }
    }

    private fun value(position: Int, source: Long): BigInt = bigIntOf(source) + offsets[position]

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = Counts()
        for (i in vars.indices) counts.excess += counts.change(value(i, state.assignment.intValue(vars[i])), 1)
        state.refPayload[factorId] = counts
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        (state.refPayload[factorId] as Counts).excess > 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as Counts).excess.toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val oldValue = state.assignment.intValue(intVar)
        if (oldValue == newValue) return 0
        val affected = positions[intVar] ?: return 0
        val counts = state.refPayload[factorId] as Counts
        var delta = 0
        for (i in affected) delta += counts.change(value(i, oldValue), -1)
        for (i in affected) delta += counts.change(value(i, newValue), 1)
        for (i in affected.asReversed()) counts.change(value(i, newValue), -1)
        for (i in affected.asReversed()) counts.change(value(i, oldValue), 1)
        return compressViolation((counts.excess + delta).toLong(), state.violationSoftCap) -
            compressViolation(counts.excess.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val newValue = state.assignment.intValue(intVar)
        if (oldValue == newValue) return 0
        val affected = positions[intVar] ?: return 0
        val counts = state.refPayload[factorId] as Counts
        val before = counts.excess
        for (i in affected) counts.excess += counts.change(value(i, oldValue), -1)
        for (i in affected) counts.excess += counts.change(value(i, newValue), 1)
        return compressViolation(counts.excess.toLong(), state.violationSoftCap) -
            compressViolation(before.toLong(), state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val counts = state.refPayload[factorId] as Counts
        if (counts.excess == 0) return
        val duplicates = counts.values.filterValues { it > 1 }.keys.sortedWith { a, b -> a.compareTo(b) }
        var selected = duplicates.first()
        for (i in duplicates.indices) if (state.rng.nextInt(i + 1) == 0) selected = duplicates[i]
        var occupant = -1
        var seen = 0
        for (i in vars.indices) {
            if (value(i, state.assignment.intValue(vars[i])) != selected) continue
            if (state.rng.nextInt(++seen) == 0) occupant = i
        }
        if (occupant < 0) return
        val variable = vars[occupant]
        val owner = state.seeding.ownerInt?.get(variable) ?: -1
        if (owner >= 0) {
            (state.factors[owner] as? AllDifferentInvariant)?.proposeOwnedSwaps(state, owner, variable, sink)
            return
        }
        proposeSourceSwaps(state, variable, sink)
        val domain = state.rootDomains[variable]
        val targets = LongArray(AllDifferentInvariant.MAX_REPAIR_TARGETS)
        var filled = 0
        var candidates = 0
        fun consider(source: Long) {
            if (value(occupant, source) in counts.values) return
            candidates++
            if (filled < targets.size) targets[filled++] = source else {
                val slot = state.rng.nextInt(candidates)
                if (slot < targets.size) targets[slot] = source
            }
        }
        if (domain.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null) {
            domain.values.forEach(::consider)
        } else {
            consider(domain.min)
            consider(domain.max)
            repeat(WIDE_DOMAIN_DRAWS) { consider(domain.randomValue(state.rng)) }
        }
        for (i in 0 until filled) sink.addChannelingIntSet(state, variable, targets[i])
        if (filled > 0) return
        val current = state.assignment.intValue(variable)
        if (current < domain.max) sink.addChannelingIntSet(state, variable, domain.higher(current))
        if (current > domain.min) sink.addChannelingIntSet(state, variable, domain.lower(current))
    }

    private fun proposeSourceSwaps(state: LocalSearchState, variable: Int, sink: MoveSink) {
        if (!sink.allowsInt(variable)) return
        val before = sink.size
        val start = state.rng.nextInt(sources.size)
        val attempts = minOf(sources.size, AllDifferentInvariant.MAX_STRUCTURED_SWAPS *
            AllDifferentInvariant.SWAP_ATTEMPT_STRIDE)
        for (step in 0 until attempts) {
            val other = sources[(start + step) % sources.size]
            if (!sink.allowsInt(other)) continue
            val move = PairSwap.intSwapMove(state, variable, other, sameShape = false, checkOwner = true) ?: continue
            sink.addCompound(move.parts)
            if (sink.size - before >= AllDifferentInvariant.MAX_STRUCTURED_SWAPS) break
        }
    }
}
