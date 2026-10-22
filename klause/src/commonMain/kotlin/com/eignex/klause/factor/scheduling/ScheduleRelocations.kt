package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.scheduling.internals.CumulativeLsState
import com.eignex.klause.factor.scheduling.internals.applyCumulativeResDelta
import com.eignex.klause.factor.scheduling.internals.applyCumulativeStartDelta
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ceilingOrNull
import com.eignex.klause.ir.randomValue
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.OptionalPresence
import com.eignex.klause.localsearch.positiveScaledBinaryDefinition
import com.eignex.klause.util.IntArrayList

/**
 * Compaction and reorder moves for a satisfied [Cumulative]. A task moves to the earliest start that
 * every scheduling resource sharing its start coordinate admits, on its current or another choice of
 * a channelled demand or presence, a task swaps order with its successor, and a window of tasks is
 * left-justified in start order. Each compound is
 * replayed on the timeline of every resource it reaches before it is offered.
 */
internal class ScheduleRelocations(
    private val own: Cumulative,
    factorId: Int,
    private val state: LocalSearchState,
    private val sink: MoveSink,
) {
    private var emitted = 0
    private var reads = READ_CAP
    private val envelopes = EnvelopeCompletion(state, sink, factorId)

    fun propose() {
        val tasks = IntArrayList()
        var latest = -1
        for (i in 0 until own.n) {
            if (!active(i)) continue
            tasks.add(i)
            if (latest < 0 || end(i) > end(latest)) latest = i
        }
        if (latest < 0) return
        compactBlock(tasks)
        reads = READ_CAP
        relocate(latest)
        val sampled = tasks[state.rng.nextInt(tasks.size)]
        if (sampled != latest) relocate(sampled)
        reorder(sampled)
    }

    private fun ownDuration(i: Int): Long =
        if (own.durationVars.isEmpty()) own.durations[i] else state.assignment.intValue(own.durationVars[i])

    private fun ownDemand(i: Int): Long =
        if (own.resourceVars.isEmpty()) own.resources[i] else state.assignment.intValue(own.resourceVars[i])

    private fun active(i: Int): Boolean =
        ownDuration(i) > 0L && ownDemand(i) > 0L && OptionalPresence.isPresentInAssignment(own.presents, i, state)

    private fun end(i: Int): Long = state.assignment.intValue(own.starts[i]) + ownDuration(i)

    private fun relocate(i: Int) {
        val task = taskAt(own.starts[i]) ?: return
        val earlier = earliestStart(task, NO_CHOICE, 0L)
        if (earlier != null) offer(listOf(Placement(task, earlier, NO_CHOICE, 0L)))
        val choice = task.choiceVar
        if (choice < 0 || !sink.allowsInt(choice) || cumulativeReads(state.projection.intOccurrences[choice])) return
        val current = state.assignment.intValue(choice)
        val domain = state.rootDomains[choice]
        val values = domain.spanOrNull(CHOICE_SPAN)
        val offset = state.rng.nextInt(CHOICE_SPAN.toInt())
        repeat(CHOICE_CAP) { k ->
            if (emitted >= MOVE_CAP || reads <= 0) return
            val value = when (values) {
                null -> domain.randomValue(state.rng)
                else -> values.valueAt((offset + k) % values.size)
            }
            if (value == current) return@repeat
            val target = earliestStart(task, choice, value) ?: return@repeat
            offer(listOf(Placement(task, target, choice, value)))
        }
    }

    private fun reorder(i: Int) {
        val iEnd = end(i)
        var j = -1
        for (k in 0 until own.n) {
            if (k == i || !active(k)) continue
            val start = state.assignment.intValue(own.starts[k])
            if (start >= iEnd && (j < 0 || start < state.assignment.intValue(own.starts[j]))) j = k
        }
        if (j < 0 || own.starts[i] == own.starts[j]) return
        val first = taskAt(own.starts[i]) ?: return
        val second = taskAt(own.starts[j]) ?: return
        val origin = first.current
        val shifted = origin + ownDuration(j)
        if (shifted < origin || state.rootDomains[second.start].ceilingOrNull(origin) != origin ||
            state.rootDomains[first.start].ceilingOrNull(shifted) != shifted
        ) return
        offer(listOf(Placement(second, origin, NO_CHOICE, 0L), Placement(first, shifted, NO_CHOICE, 0L)))
    }

    private fun taskAt(start: Int): Task? {
        if (!sink.allowsInt(start)) return null
        val footprints = ArrayList<Footprint>(4)
        var choice = -1
        for (fid in state.projection.intOccurrences[start]) {
            val factor = state.problem.factors[fid]
            if (factor is Diffn) return null
            val cumulative = factor as? Cumulative ?: continue
            val ls = state.refPayload[fid] as? CumulativeLsState ?: return null
            if (state.factors[fid] !is CumulativeInvariant && state.factors[fid] !is DisjunctiveInvariant) return null
            val j = cumulative.startPosOf(start)
            if (j < 0 || start in cumulative.repeatedIntVars) return null
            val footprint = footprint(fid, cumulative, j, ls) ?: return null
            if (choice < 0) choice = footprint.demandChannel?.input ?: footprint.presenceChannel?.input ?: -1
            footprints.add(footprint)
        }
        val current = state.assignment.intValue(start)
        val domain = state.rootDomains[start]
        for (footprint in footprints) {
            if (current > Long.MAX_VALUE - footprint.duration || domain.max > Long.MAX_VALUE - footprint.duration) {
                return null
            }
        }
        return Task(start, current, footprints, choice)
    }

    private fun footprint(fid: Int, cumulative: Cumulative, j: Int, ls: CumulativeLsState): Footprint? {
        val duration = if (cumulative.durationVars.isEmpty()) {
            cumulative.durations[j]
        } else {
            state.assignment.intValue(cumulative.durationVars[j])
        }
        if (duration < 0L) return null
        val demandVar = if (cumulative.resourceVars.isEmpty()) -1 else cumulative.resourceVars[j]
        if (demandVar >= 0 && demandVar in cumulative.repeatedIntVars) return null
        val demand = if (demandVar < 0) cumulative.resources[j] else state.assignment.intValue(demandVar)
        val presence = if (cumulative.presents.isEmpty()) -1 else cumulative.presents[j]
        if (presence >= 0 && Lit.variable(presence) in cumulative.repeatedPresenceVars) return null
        val present = OptionalPresence.isPresentInAssignment(cumulative.presents, j, state)
        val demandChannel = if (demandVar < 0) null else demandChannel(demandVar)
        val presenceChannel = if (presence < 0) null else presenceChannel(presence)
        // A channel that disagrees with the maintained value would replay a footprint the timeline does not hold.
        return Footprint(
            fid,
            ls,
            duration,
            demand,
            present,
            demandVar,
            presence,
            demandChannel?.takeIf { it.demand(current(it)) == demand },
            presenceChannel?.takeIf { it.holds(current(it)) == present },
        )
    }

    private fun current(channel: Channel): Long = state.assignment.intValue(channel.input)

    private fun demandChannel(output: Int): Channel? {
        val net = state.invariants ?: return null
        var flag = output
        var scale = 1L
        val literal = net.literalChannel(output)?.literal ?: run {
            val (input, multiple) = positiveScaledBinaryDefinition(state, output) ?: return null
            flag = input
            scale = multiple
            net.literalChannel(input)?.literal ?: return null
        }
        val flagDomain = state.rootDomains[flag]
        if (flagDomain.min != 0L || flagDomain.max != 1L) return null
        if (0L !in state.rootDomains[output] || scale !in state.rootDomains[output]) return null
        val predicate = net.equalityPredicate(Lit.variable(literal)) ?: return null
        return Channel(predicate.input, predicate.value, Lit.isPositive(literal), scale)
    }

    private fun presenceChannel(literal: Int): Channel? {
        val predicate = state.invariants?.equalityPredicate(Lit.variable(literal)) ?: return null
        return Channel(predicate.input, predicate.value, Lit.isPositive(literal), 1L)
    }

    /**
     * Left-justifies a window of the resource's tasks in start order as one compound, each task placed on the
     * timelines its predecessors in the window already moved on.
     */
    private fun compactBlock(tasks: IntArrayList) {
        if (tasks.size < 2) return
        val order = tasks.toIntArray().sortedBy { state.assignment.intValue(own.starts[it]) }
        val first = if (order.size <= BLOCK_CAP) 0 else state.rng.nextInt(order.size - BLOCK_CAP + 1)
        val placements = ArrayList<Placement>()
        val timelines = ArrayList<CumulativeLsState>()
        val before = ArrayList<Long>()
        var floor = Long.MIN_VALUE
        var admitted = false
        try {
            for (i in order.subList(first, minOf(order.size, first + BLOCK_CAP))) {
                val task = taskAt(own.starts[i]) ?: continue
                val target = earliestStart(task, NO_CHOICE, 0L, floor)
                floor = target ?: task.current
                if (target == null) continue
                val placement = Placement(task, target, NO_CHOICE, 0L)
                val cost = task.footprints.sumOf { if (it.effective(NO_CHOICE, 0L) > 0L) 2 * it.duration else 0L }
                if (cost > reads) break
                reads -= cost.toInt()
                for (footprint in task.footprints) {
                    if (timelines.none { it === footprint.ls }) {
                        timelines.add(footprint.ls)
                        before.add(footprint.ls.overage)
                    }
                    shift(footprint, placement, true)
                }
                placements.add(placement)
            }
            admitted = timelines.indices.all { timelines[it].overage <= before[it] }
        } finally {
            for (placement in placements.asReversed()) {
                for (footprint in placement.task.footprints.asReversed()) shift(footprint, placement, false)
            }
        }
        if (admitted && placements.size >= 2) offer(placements, replayed = true)
    }

    /** Earliest start below the current one, from [from] on, whose window every footprint admits. */
    private fun earliestStart(task: Task, choiceVar: Int, choice: Long, from: Long = Long.MIN_VALUE): Long? {
        val domain = state.rootDomains[task.start]
        var t = maxOf(domain.min, from)
        while (reads > 0) {
            t = domain.ceilingOrNull(t) ?: return null
            if (t >= task.current) return null
            var next = t
            for (footprint in task.footprints) {
                val demand = footprint.effective(choiceVar, choice)
                if (demand <= 0L || footprint.duration <= 0L) continue
                val conflict = lastConflict(footprint, task.current, t, demand)
                if (reads <= 0) return null
                if (conflict != NO_CONFLICT) {
                    next = conflict + 1
                    break
                }
            }
            if (next == t) return t
            t = next
        }
        return null
    }

    private fun lastConflict(footprint: Footprint, current: Long, start: Long, demand: Long): Long {
        val ls = footprint.ls
        val mine = footprint.effective(NO_CHOICE, 0L)
        val end = current + footprint.duration
        var time = start + footprint.duration - 1
        while (time >= start) {
            if (--reads < 0) return NO_CONFLICT
            val slot = time - ls.tLow
            val load = if (slot >= 0L && slot < ls.usage.size) ls.usage[slot.toInt()] else 0L
            val released = if (time in current until end) mine else 0L
            if (load - released + demand > ls.cap) return time
            time--
        }
        return NO_CONFLICT
    }

    private fun offer(placements: List<Placement>, replayed: Boolean = false) {
        if (emitted >= MOVE_CAP || (!replayed && reads <= 0)) return
        val choice = placements.firstOrNull { it.choiceVar >= 0 }
        val geometry = ArrayList<Move.IntSet>(placements.size + 1)
        for (placement in placements) geometry.add(Move.IntSet(placement.task.start, placement.target))
        val channel = if (choice == null) emptyList<Move>() else channelParts(choice.choiceVar, choice.choice) ?: return
        if (choice != null) geometry.add(Move.IntSet(choice.choiceVar, choice.choice))
        if (!reachesOnlyFootprints(placements, geometry, channel) || (!replayed && !admissible(placements))) return
        val completed = envelopes.complete(geometry)
        val parts = ArrayList<Move>(completed.size + channel.size)
        val isolated = completed.subList(geometry.size, completed.size).all { isolatedEnvelope(it.varId) }
        parts.addAll(if (isolated) completed else geometry)
        parts.addAll(channel)
        sink.addCompound(parts)
        emitted++
    }

    /** The coordinated indicator writes of setting [choiceVar] to [value], or null when one is not admissible. */
    private fun channelParts(choiceVar: Int, value: Long): List<Move>? {
        val move = state.synthesizeChannelingMove(choiceVar, value) as? Move.Compound ?: return emptyList()
        val net = state.invariants
        // Definitions recompute their outputs; the cone check accounts for them instead.
        val parts = move.parts.filterNot {
            (it is Move.IntSet && (it.varId == choiceVar || net?.isDefinedInt(it.varId) == true)) ||
                (it is Move.BoolFlip && net?.isDefinedBool(it.varId) == true)
        }
        for (part in parts) {
            when (part) {
                is Move.BoolFlip -> if (cumulativeReads(state.projection.boolOccurrences[part.varId])) return null
                is Move.IntSet -> {
                    if (!sink.allowsInt(part.varId) || cumulativeReads(state.projection.intOccurrences[part.varId])) {
                        return null
                    }
                }
                else -> return null
            }
        }
        return parts
    }

    /** Whether every scheduling coordinate the move's definitions recompute is a decoded footprint channel. */
    private fun reachesOnlyFootprints(
        placements: List<Placement>,
        geometry: List<Move.IntSet>,
        channel: List<Move>,
    ): Boolean {
        val net = state.invariants ?: return true
        val ints = IntArrayList(geometry.size + channel.size)
        val bools = IntArrayList(channel.size)
        for (part in geometry) ints.add(part.varId)
        for (part in channel) {
            if (part is Move.BoolFlip) bools.add(part.varId) else ints.add((part as Move.IntSet).varId)
        }
        val choice = placements.firstOrNull { it.choiceVar >= 0 }?.choiceVar ?: -1
        for (index in net.affectedNodes(ints.toIntArray(), bools.toIntArray())) {
            val node = net.node(index)
            val readers = if (node.outIsBool) {
                state.projection.boolOccurrences[node.out]
            } else {
                state.projection.intOccurrences[node.out]
            }
            for (fid in readers) {
                if (state.problem.factors[fid] !is Cumulative) continue
                val decoded = placements.any { placement ->
                    placement.task.footprints.any { it.decodes(fid, node.out, node.outIsBool, choice) }
                }
                if (!decoded) return false
            }
        }
        return true
    }

    private fun isolatedEnvelope(variable: Int): Boolean =
        state.invariants?.readsInt(variable) != true && !cumulativeReads(state.projection.intOccurrences[variable])

    private fun cumulativeReads(factorIds: IntArray): Boolean =
        factorIds.any { state.problem.factors[it] is Cumulative }

    /** Replays [placements] on every footprint's timeline and accepts them when no resource gains overload. */
    private fun admissible(placements: List<Placement>): Boolean {
        var cost = 0L
        for (placement in placements) {
            for (footprint in placement.task.footprints) {
                val loaded = footprint.effective(NO_CHOICE, 0L) > 0L ||
                    footprint.effective(placement.choiceVar, placement.choice) > 0L
                if (loaded) cost += 2 * footprint.duration
            }
        }
        if (cost > reads) return false
        reads -= cost.toInt()
        val timelines = ArrayList<CumulativeLsState>()
        val before = ArrayList<Long>()
        for (placement in placements) {
            for (footprint in placement.task.footprints) {
                if (timelines.none { it === footprint.ls }) {
                    timelines.add(footprint.ls)
                    before.add(footprint.ls.overage)
                }
            }
        }
        for (placement in placements) for (footprint in placement.task.footprints) shift(footprint, placement, true)
        try {
            return timelines.indices.all { timelines[it].overage <= before[it] }
        } finally {
            for (placement in placements.asReversed()) {
                for (footprint in placement.task.footprints.asReversed()) shift(footprint, placement, false)
            }
        }
    }

    private fun shift(footprint: Footprint, placement: Placement, forward: Boolean) {
        val ls = footprint.ls
        val duration = footprint.duration
        if (duration <= 0L) return
        val now = footprint.effective(NO_CHOICE, 0L).coerceAtLeast(0L)
        val next = footprint.effective(placement.choiceVar, placement.choice).coerceAtLeast(0L)
        val fromStart = if (forward) placement.task.current else placement.target
        val toStart = if (forward) placement.target else placement.task.current
        val fromDemand = if (forward) now else next
        val toDemand = if (forward) next else now
        if (fromDemand == toDemand) {
            if (fromDemand > 0L) applyCumulativeStartDelta(ls, fromStart, toStart, duration, fromDemand)
        } else {
            if (fromDemand > 0L) applyCumulativeResDelta(ls, fromStart, duration, fromDemand, 0L)
            if (toDemand > 0L) applyCumulativeResDelta(ls, toStart, duration, 0L, toDemand)
        }
    }

    /** `output = scale · [input = value]` for a positive literal, its complement otherwise. */
    private class Channel(val input: Int, val value: Long, val positive: Boolean, val scale: Long) {
        fun holds(choice: Long): Boolean = (choice == value) == positive

        fun demand(choice: Long): Long = if (holds(choice)) scale else 0L
    }

    /** One task's demand on one resource that shares its start coordinate. */
    private class Footprint(
        val factorId: Int,
        val ls: CumulativeLsState,
        val duration: Long,
        val demand: Long,
        val present: Boolean,
        val demandVar: Int,
        val presence: Int,
        val demandChannel: Channel?,
        val presenceChannel: Channel?,
    ) {
        fun effective(choiceVar: Int, choice: Long): Long {
            val presenceChoice = presenceChannel?.takeIf { it.input == choiceVar }
            if (!(presenceChoice?.holds(choice) ?: present)) return 0L
            return demandChannel?.takeIf { it.input == choiceVar }?.demand(choice) ?: demand
        }

        fun decodes(fid: Int, variable: Int, isBool: Boolean, choiceVar: Int): Boolean = fid == factorId &&
            if (isBool) {
                presence >= 0 && Lit.variable(presence) == variable && presenceChannel?.input == choiceVar
            } else {
                demandVar == variable && demandChannel?.input == choiceVar
            }
    }

    private class Task(val start: Int, val current: Long, val footprints: List<Footprint>, val choiceVar: Int)

    private class Placement(val task: Task, val target: Long, val choiceVar: Int, val choice: Long)

    private companion object {
        const val NO_CHOICE = -1
        const val NO_CONFLICT = Long.MIN_VALUE
        const val READ_CAP = 32_768
        const val MOVE_CAP = 4
        const val CHOICE_CAP = 3
        const val CHOICE_SPAN = 16L
        const val BLOCK_CAP = 32
    }
}
