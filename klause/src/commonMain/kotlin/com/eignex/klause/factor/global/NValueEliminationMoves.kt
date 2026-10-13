package com.eignex.klause.factor.global

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ceilingOrNull
import com.eignex.klause.ir.floorOrNull
import com.eignex.klause.localsearch.ChannelingSink
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongHashSet

internal class NValueEliminationMoves(
    private val n: Int,
    private val xs: IntArray,
    private val mode: NValue.Mode,
    private val presents: IntArray,
    private val present: (LocalSearchState, Int) -> Boolean,
) {
    fun propose(state: LocalSearchState, distinct: Int, sink: MoveSink) {
        if (distinct < 2) return
        val targetN = countTarget(state, distinct - 1, sink) ?: return
        if (targetN != state.assignment.intValue(n) && !sink.allowsInt(n)) return
        var emitted = 0
        val attempted = HashSet<Pair<Long, Long>>()
        repeat(MAX_ATTEMPTS) {
            if (emitted >= MAX_MOVES) return
            val from = state.rng.nextInt(xs.size)
            val to = state.rng.nextInt(xs.size)
            if (!present(state, from) || !present(state, to)) return@repeat
            val old = state.assignment.intValue(xs[from])
            val target = state.assignment.intValue(xs[to])
            if (old == target || !attempted.add(old to target)) return@repeat
            val parts = classTargets(state, old, target, sink) ?: return@repeat
            if (parts.any { it.varId == n && it.newValue != targetN }) return@repeat
            if (parts.none { it.varId == n } && targetN != state.assignment.intValue(n)) {
                parts += Move.IntSet(n, targetN)
            }
            if (queue(state, parts, sink)) emitted++
        }
    }

    private fun countTarget(state: LocalSearchState, distinct: Int, sink: MoveSink): Long? {
        val current = state.assignment.intValue(n)
        val domain = state.rootDomains[n]
        return when (mode) {
            NValue.Mode.Eq -> distinct.toLong().takeIf { it in domain }
            NValue.Mode.AtLeast -> if (current <= distinct) current else domain.floorOrNull(distinct.toLong())
            NValue.Mode.AtMost -> if (!sink.allowsInt(n) && current >= distinct) current else {
                domain.ceilingOrNull(distinct.toLong())
            }
        }
    }

    private fun classTargets(
        state: LocalSearchState,
        old: Long,
        target: Long,
        sink: MoveSink,
    ): ArrayList<Move.IntSet>? {
        val seen = IntHashSet()
        val parts = ArrayList<Move.IntSet>()
        for (i in xs.indices) {
            val variable = xs[i]
            if (!present(state, i) || state.assignment.intValue(variable) != old || !seen.add(variable)) continue
            if (parts.size >= MAX_COORDINATES || !sink.allowsInt(variable) || target !in state.rootDomains[variable]) {
                return null
            }
            parts += Move.IntSet(variable, target)
        }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun queue(state: LocalSearchState, parts: List<Move.IntSet>, sink: MoveSink): Boolean {
        val first = parts[0]
        val coordinated = ChannelingSink(first.varId, first.newValue)
        // Counter-shifts must not resurrect the removed class or change its surviving destination.
        for (variable in xs) coordinated.pin(variable)
        coordinated.pin(n)
        for (i in 1 until parts.size) coordinated.add(parts[i])
        for (coordinate in parts) {
            for (fid in state.projection.intOccurrences[coordinate.varId]) {
                state.factors[fid].contributeChanneling(
                    state, fid, coordinate.varId, state.assignment.intValue(coordinate.varId), coordinate.newValue,
                    coordinated,
                )
            }
        }
        coordinated.carryBinaryChannels(state, sink)
        val move = coordinated.toMove()
        val complete = if (move is Move.Compound) move.parts else listOf(move)
        if (!preservesCount(state, complete)) return false
        val before = sink.size
        sink.addCompound(complete)
        return sink.size > before
    }

    private fun preservesCount(state: LocalSearchState, parts: List<Move>): Boolean {
        val targets = parts.filterIsInstance<Move.IntSet>().associate { it.varId to it.newValue }
        val flips = parts.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        // Presence changes have a separate neighborhood; a class merge keeps its participating positions.
        if (presents.any { Lit.variable(it) in flips }) return false
        val values = LongHashSet()
        for (i in xs.indices) {
            if (present(state, i)) values.add(targets[xs[i]] ?: state.assignment.intValue(xs[i]))
        }
        val count = targets[n] ?: state.assignment.intValue(n)
        return when (mode) {
            NValue.Mode.Eq -> values.size.toLong() == count
            NValue.Mode.AtLeast -> values.size.toLong() >= count
            NValue.Mode.AtMost -> values.size.toLong() <= count
        }
    }

    private companion object {
        const val MAX_MOVES = 4
        const val MAX_ATTEMPTS = 24
        const val MAX_COORDINATES = 32
    }
}
