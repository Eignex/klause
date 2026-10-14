package com.eignex.klause.factor.global

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ceilingOrNull
import com.eignex.klause.ir.floorOrNull
import com.eignex.klause.localsearch.ChannelingSink
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.buildRepairChain
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongHashSet

internal class NValueEliminationMoves(
    private val n: Int,
    private val xs: IntArray,
    private val mode: NValue.Mode,
    private val presents: IntArray,
    private val present: (LocalSearchState, Int) -> Boolean,
) {
    fun propose(
        state: LocalSearchState,
        factorId: Int,
        distinct: Int,
        sink: MoveSink,
        allowRepairChain: Boolean = true,
    ) {
        if (distinct < 2) return
        // A searched input can rewrite a maintained coordinate in another value class.
        val network = state.invariants
        if (network != null && (
                network.isDefinedInt(n) || xs.any { network.isDefinedInt(it) } ||
                    presents.any { network.isDefinedBool(Lit.variable(it)) }
                )
        ) {
            return
        }
        val targetN = countTarget(state, distinct - 1, sink) ?: return
        if (targetN != state.assignment.intValue(n) && !sink.allowsInt(n)) return
        val representatives = classRepresentatives(state)
        if (representatives.size < 2) return
        var emitted = 0
        var completion: Move? = null
        var completionDelta = Long.MAX_VALUE
        var coordinator: NValueClauseCompletion? = null
        val canComplete = state.cost == 0L || (state.violated.size == 1 && state.violated.contains(factorId))
        val attempted = HashSet<Pair<Long, Long>>()
        repeat(MAX_ATTEMPTS) {
            if (emitted >= MAX_MOVES) return@repeat
            val from = representatives[state.rng.nextInt(representatives.size)]
            val old = state.assignment.intValue(from)
            val target = sampleTarget(state, from, representatives) ?: return@repeat
            if (!attempted.add(old to target)) return@repeat
            val parts = classTargets(state, old, target, sink) ?: return@repeat
            if (parts.any { it.varId == n && it.newValue != targetN }) return@repeat
            if (parts.none { it.varId == n } && targetN != state.assignment.intValue(n)) {
                parts += Move.IntSet(n, targetN)
            }
            val move = queue(state, parts, sink, distinct - 1) ?: return@repeat
            emitted++
            if (canComplete) {
                val delta = state.netDelta(move)
                if (delta >= 0L && (state.cost > 0L || delta > 0L)) {
                    val candidate = if (state.invariants == null) move else {
                        val clauses = coordinator ?: NValueClauseCompletion(state, sink, xs + intArrayOf(n))
                            .also { created -> coordinator = created }
                        clauses.complete(move)
                    }
                    val candidateParts = if (candidate is Move.Compound) candidate.parts else listOf(candidate)
                    if (candidateParts.size > MAX_COMPLETED_PARTS ||
                        !preservesCount(state, candidateParts, distinct - 1)
                    ) return@repeat
                    val reconciled = state.netDelta(candidate)
                    if (reconciled < completionDelta) {
                        completion = candidate
                        completionDelta = reconciled
                    }
                }
            }
        }
        completion?.let { completeMove(state, it, sink, distinct - 1, allowRepairChain) }
    }

    private fun classRepresentatives(state: LocalSearchState): List<Int> {
        val values = LongHashSet()
        val representatives = ArrayList<Int>()
        for (i in xs.indices) {
            if (present(state, i) && values.add(state.assignment.intValue(xs[i]))) representatives += xs[i]
        }
        return representatives
    }

    private fun sampleTarget(state: LocalSearchState, from: Int, representatives: List<Int>): Long? {
        val old = state.assignment.intValue(from)
        val domain = state.rootDomains[from]
        var target: Long? = null
        var seen = 0
        for (variable in representatives) {
            val value = state.assignment.intValue(variable)
            if (value != old && value in domain) {
                seen++
                if (state.rng.nextInt(seen) == 0) target = value
            }
        }
        return target
    }

    private fun countTarget(state: LocalSearchState, distinct: Int, sink: MoveSink): Long? {
        val current = state.assignment.intValue(n)
        val domain = state.rootDomains[n]
        return when (mode) {
            NValue.Mode.Eq -> distinct.toLong().takeIf { it in domain }

            NValue.Mode.AtLeast -> if (current <= distinct) current else domain.floorOrNull(distinct.toLong())

            NValue.Mode.AtMost -> if (!sink.allowsInt(n) && current >= distinct) {
                current
            } else {
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

    private fun queue(
        state: LocalSearchState,
        parts: List<Move.IntSet>,
        sink: MoveSink,
        expectedDistinct: Int,
    ): Move? {
        val first = parts[0]
        val coordinated = ChannelingSink(first.varId, first.newValue)
        // Counter-shifts must not resurrect the removed class or change its surviving destination.
        for (variable in xs) coordinated.pin(variable)
        coordinated.pin(n)
        for (i in 1 until parts.size) coordinated.add(parts[i])
        for (coordinate in parts) {
            if (coordinate.varId == n) {
                for (fid in state.projection.intOccurrences[n]) {
                    state.factors[fid].contributeChanneling(
                        state,
                        fid,
                        n,
                        state.assignment.intValue(n),
                        coordinate.newValue,
                        coordinated,
                    )
                }
            } else {
                // Per-coordinate counter-shifts cannot reconcile a row's combined class drift.
                val channeling = state.synthesizeChannelingMove(coordinate.varId, coordinate.newValue)
                if (channeling is Move.Compound) {
                    for (part in channeling.parts) {
                        if (part is Move.BoolFlip) coordinated.addIndicatorFlip(part.varId)
                    }
                }
            }
        }
        coordinated.carryBinaryChannels(state, sink)
        val move = coordinated.toMove()
        val coordinatedParts = if (move is Move.Compound) move.parts else listOf(move)
        if (!preservesCount(state, coordinatedParts, expectedDistinct)) return null
        val before = sink.size
        sink.addCompound(coordinatedParts)
        if (sink.size == before) return null
        val eligible = coordinatedParts.filter {
            when (it) {
                is Move.IntSet -> sink.allowsInt(it.varId)
                is Move.BoolFlip -> sink.allowsBool(it.varId)
                else -> true
            }
        }
        return if (eligible.size == 1) eligible[0] else Move.Compound(eligible)
    }

    private fun completeMove(
        state: LocalSearchState,
        move: Move,
        sink: MoveSink,
        expectedDistinct: Int,
        allowRepairChain: Boolean,
    ) {
        val firstParts = if (move is Move.Compound) move.parts else listOf(move)
        if (firstParts.size > MAX_COMPLETED_PARTS || !preservesCount(state, firstParts, expectedDistinct)) return
        if (state.netDelta(move) == -state.cost) {
            sink.addCompound(firstParts)
            return
        }
        // Repair proposals can be requested while the caller owns a live repair walk.
        if (!allowRepairChain) return
        val repairs = MoveSink(state.assumptions.withInt(n, state.assignment.intValue(n)))
        repairs.setInvariants(state.invariants)
        repairs.setOwners(state.seeding.ownerInt)
        val parts = state.buildRepairChain(move, MAX_REPAIR_DEPTH, repairs) ?: return
        if (parts.size > MAX_COMPLETED_PARTS || !preservesCount(state, parts, expectedDistinct)) return
        val candidate = if (parts.size == 1) parts[0] else Move.Compound(parts)
        if (state.netDelta(candidate) == -state.cost) sink.addCompound(parts)
    }

    private fun preservesCount(state: LocalSearchState, parts: List<Move>, expectedDistinct: Int): Boolean {
        val targets = parts.filterIsInstance<Move.IntSet>().associate { it.varId to it.newValue }
        val flips = parts.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        // Presence changes have a separate neighborhood; a class merge keeps its participating positions.
        if (presents.any { Lit.variable(it) in flips }) return false
        val values = LongHashSet()
        for (i in xs.indices) {
            if (present(state, i)) values.add(targets[xs[i]] ?: state.assignment.intValue(xs[i]))
        }
        if (values.size != expectedDistinct) return false
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
        const val MAX_REPAIR_DEPTH = 8
        const val MAX_COMPLETED_PARTS = 128
    }
}
