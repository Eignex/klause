package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.util.Cancellation

internal class CompletedMoveEvaluation(
    private val state: LocalSearchState,
    private val objective: Objective,
    private val cancellation: Cancellation,
    private val mayAttempt: () -> Boolean,
    private val complete: (Sample) -> Completion,
    private val current: () -> Sample,
) {
    private val witnesses = mutableMapOf<Move, Sample>()
    private val grades = mutableMapOf<Move, Double>()
    private var coordinate = 0
    private var direction = 0
    private var proposal = 0
    private var exploration: Move? = null
    private val seedSink = MoveSink()
    var productiveMove: Move? = null
        private set

    fun begin() {
        witnesses.clear()
        grades.clear()
        exploration = null
        productiveMove = null
    }

    fun seed(destination: MoveSink) {
        val sink = seedSink
        sink.clear()
        sink.setAssumptions(state.assumptions)
        sink.setInvariants(state.invariants)
        sink.setOwners(state.seeding.ownerInt)
        val problem = state.model.problem
        val count = problem.numBoolVars + problem.numIntVars
        if (count == 0) return
        val selected = coordinate
        val activeDirection = direction
        coordinate = (coordinate + 1) % count
        if (coordinate == 0) direction = (direction + 1) % 4
        if (selected < problem.numBoolVars) {
            val occurrences = state.projection.boolOccurrences[selected]
            if (occurrences.none { problem.factors[it].variables.reals.isNotEmpty() }) return
            sink.addChannelingBoolFlip(state, selected)
        } else {
            val id = selected - problem.numBoolVars
            val occurrences = state.projection.intOccurrences[id]
            if (occurrences.none { problem.factors[it].variables.reals.isNotEmpty() }) return
            val current = state.assignment.intValue(id)
            val domain = state.rootDomains[id]
            if (domain.min == domain.max) return
            val target = when (activeDirection) {
                0 -> if (current < domain.max) domain.higher(current) else domain.lower(current)
                1 -> if (current > domain.min) domain.lower(current) else domain.higher(current)
                2 -> domain.max
                else -> domain.min
            }
            if (target == current || target !in domain) return
            sink.addChannelingIntSet(state, id, target)
        }
        exploration = sink.list.firstOrNull()
        for (move in sink.list) {
            if (move in destination.list) continue
            when (move) {
                is Move.BoolFlip -> destination.addBoolFlip(move.varId)
                is Move.IntSet -> destination.addIntSet(move.varId, move.newValue)
                is Move.RealSet -> destination.addRealSet(move.varId, move.newValue)
                is Move.Compound -> destination.addCompound(move.parts)
            }
        }
    }

    fun prepare(moves: List<Move>, rawScore: (Move) -> Double) {
        if (moves.isEmpty()) return
        val distinct = moves.distinct()
        val exploring = exploration?.takeIf { it in distinct }
            ?: distinct[proposal % distinct.size]
        proposal = if (proposal == Int.MAX_VALUE) 0 else proposal + 1
        val ranked = AcceptanceRule.Greedy.choose(state.rng, moves, emptyList(), 1.0, rawScore)
        if (ranked != null && rawScore(ranked) < 0.0) {
            attempt(ranked)
            if ((score(ranked) ?: Double.POSITIVE_INFINITY) < 0.0) {
                productiveMove = ranked
                return
            }
        }
        attempt(exploring)
        if (ranked != null && ranked != exploring) attempt(ranked)
    }

    fun score(move: Move): Double? = grades[move]

    fun sample(move: Move): Sample? = witnesses[move]

    fun attempt(move: Move): Sample? {
        witnesses[move]?.let { return it }
        if (grades.containsKey(move) || cancellation() || !mayAttempt()) return null
        grades[move] = Double.POSITIVE_INFINITY
        val candidate = state.completionCandidate(move) ?: return null
        val decided = complete(candidate)
        if (decided !is Completion.Witness || cancellation()) return null
        witnesses[move] = decided.sample
        val before = current()
        grades[move] = if (objective is LinearObjective && decided.sample.symbolicReals == null &&
            decided.sample.algebraicReals == null && before.symbolicReals == null && before.algebraicReals == null
        ) {
            (objective.evaluateExact(decided.sample) - objective.evaluateExact(before)).toDouble()
        } else {
            objective.evaluate(decided.sample) - objective.evaluate(before)
        }
        return decided.sample
    }

    fun commitMove(move: Move, sample: Sample): Move? {
        val parts = (if (move is Move.Compound) move.parts else listOf(move)).filter {
            it !is Move.RealSet
        }.toMutableList()
        for (id in 0 until sample.numRealVars) {
            val value = sample.approximateRealValue(id)
            if (value.toRawBits() != state.assignment.realValue(id).toRawBits()) parts.add(Move.RealSet(id, value))
        }
        return when (parts.size) {
            0 -> null
            1 -> parts.single()
            else -> Move.Compound(parts)
        }
    }
}
