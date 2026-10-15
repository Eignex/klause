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
    private val finiteObjective = objective !is LinearObjective ||
        objective.intCoefficientValues.any { it != 0L } || objective.boolWeightValues.any { it != 0L }
    private val witnesses = mutableMapOf<Move, Sample>()
    private val grades = mutableMapOf<Move, Double>()
    private var base: Sample? = null
    private var coordinate = 0
    private var direction = 0
    private var proposal = 0
    private var exploration: Move? = null
    private val seedSink = MoveSink()
    var productiveMove: Move? = null
        private set
    var ordinaryMove: Move? = null
        private set

    fun begin() {
        val sample = current()
        if (sample != base) {
            witnesses.clear()
            grades.clear()
            base = sample
        } else {
            while (witnesses.size > 2) witnesses.remove(witnesses.keys.first())
            grades.keys.retainAll(witnesses.keys)
        }
        exploration = null
        productiveMove = null
        ordinaryMove = null
    }

    fun retainOrdinary(move: Move) {
        ordinaryMove = move
    }

    fun increasesObjective(move: Move): Boolean {
        if (!changesFinite(move)) return false
        val realGuess = move is Move.RealSet || move is Move.Compound && move.parts.any { it is Move.RealSet }
        if (!finiteObjective && !realGuess) return false
        return (state.objectiveDelta(objective, move) ?: 0.0) > 0.0
    }

    fun seed(destination: MoveSink, limit: Int = Int.MAX_VALUE) {
        if (limit <= 0) return
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
        var added = 0
        for (move in sink.list) {
            if (move in destination.list) continue
            if (added == limit) break
            added++
            when (move) {
                is Move.BoolFlip -> destination.addBoolFlip(move.varId)
                is Move.IntSet -> destination.addIntSet(move.varId, move.newValue)
                is Move.RealSet -> destination.addRealSet(move.varId, move.newValue)
                is Move.Compound -> destination.addCompound(move.parts)
            }
        }
    }

    fun prepare(moves: List<Move>, deterministic: Boolean = false, rawScore: (Move) -> Double) {
        if (cancellation() || !mayAttempt()) return
        val finiteMoves = moves.filter(::changesFinite)
        if (finiteMoves.isEmpty()) return
        val distinct = finiteMoves.distinct()
        val exploring = exploration?.takeIf { it in distinct }
            ?: distinct[proposal % distinct.size]
        proposal = if (proposal == Int.MAX_VALUE) 0 else proposal + 1
        val ranked = if (deterministic) finiteMoves.minByOrNull(rawScore) else {
            AcceptanceRule.Greedy.choose(state.rng, finiteMoves, emptyList(), 1.0, rawScore)
        }
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
        witnesses.remove(move)?.let {
            witnesses[move] = it
            return it
        }
        if (grades.containsKey(move) || !changesFinite(move) || cancellation() || !mayAttempt()) return null
        grades[move] = Double.POSITIVE_INFINITY
        val candidate = state.completionCandidate(move) ?: return null
        val before = current()
        // The real subproblem depends on finite pins; changing only a real guess repeats its query.
        if (candidate.hammingDistanceTo(before) == 0) return null
        val decided = complete(candidate)
        if (decided !is Completion.Witness || cancellation()) return null
        witnesses[move] = decided.sample
        grades[move] = if (objective is LinearObjective && decided.sample.symbolicReals == null &&
            decided.sample.algebraicReals == null && before.symbolicReals == null && before.algebraicReals == null
        ) {
            (objective.evaluateExact(decided.sample) - objective.evaluateExact(before)).toDouble()
        } else {
            objective.evaluate(decided.sample) - objective.evaluate(before)
        }
        return decided.sample
    }

    private fun changesFinite(move: Move): Boolean = when (move) {
        is Move.BoolFlip -> true
        is Move.IntSet -> move.newValue != state.assignment.intValue(move.varId)
        is Move.RealSet -> false
        is Move.Compound -> move.parts.any(::changesFinite)
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
