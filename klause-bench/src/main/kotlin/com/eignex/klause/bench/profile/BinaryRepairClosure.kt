package com.eignex.klause.bench.profile

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.MoveSource
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.util.Cancellation

internal class BinaryRepairGraph private constructor(
    val edges: Array<IntArray>,
    val units: IntArray,
    val naryClauses: Array<IntArray>,
    val naryOccurrences: Array<IntArray>,
    private val factorIds: IntArray,
) {
    val edgeCount: Int = edges.sumOf { it.size }

    fun violations(state: LocalSearchState): Long = factorIds.sumOf { state.factorDegree[it].toLong() }

    companion object {
        fun prepare(
            problem: Problem, pins: Assumptions, cancellation: Cancellation, includeNary: Boolean,
        ): BinaryRepairGraph {
            val edges = Array(problem.numBoolVars * 2) { ArrayList<Int>() }
            val units = ArrayList<Int>()
            val factors = ArrayList<Int>()
            val nary = ArrayList<IntArray>()
            val occurrences = Array(problem.numBoolVars * 2) { ArrayList<Int>() }
            var visits = 0
            for ((id, factor) in problem.factors.withIndex()) {
                val residual = ArrayList<Int>()
                var first = Lit.NONE
                var second = Lit.NONE
                var count = 0
                var satisfied = false
                for (literal in (factor as Clause).literals) {
                    if (visits++ % 256 == 0) check(!cancellation.isCancelled())
                    val pinned = pins.boolValueOrNull(Lit.variable(literal))
                    if (pinned != null) {
                        if (Lit.evaluate(literal, pinned)) {
                            satisfied = true
                            break
                        }
                    } else {
                        if (count == 0) first = literal
                        if (count == 1) second = literal
                        count++
                        residual.add(literal)
                    }
                }
                if (satisfied) continue
                check(count > 0)
                if (count > 2) {
                    if (includeNary) {
                        val row = nary.size
                        nary.add(residual.toIntArray())
                        for (literal in residual) occurrences[Lit.negate(literal)].add(row)
                    }
                    continue
                }
                factors.add(id)
                if (count == 1) units.add(first) else {
                    edges[Lit.negate(first)].add(second)
                    edges[Lit.negate(second)].add(first)
                }
            }
            val packed = Array(edges.size) {
                if (it % 256 == 0) check(!cancellation.isCancelled())
                edges[it].distinct().toIntArray()
            }
            val packedOccurrences = Array(occurrences.size) {
                if (it % 256 == 0) check(!cancellation.isCancelled())
                occurrences[it].toIntArray()
            }
            return BinaryRepairGraph(packed, units.toIntArray(), nary.toTypedArray(),
                packedOccurrences, factors.toIntArray())
        }
    }
}

internal class BinaryRepairClosure(private val graph: BinaryRepairGraph, private val assumptions: Assumptions) {
    private val size = graph.edges.size / 2
    private val stamps = IntArray(size)
    private val values = BooleanArray(size)
    private val queue = IntArray(size)
    private val seedStamps = IntArray(size)
    private val seedParity = BooleanArray(size)
    private val seeds = IntArray(size)
    private val frozen = ByteArray(size) { -1 }.also { array ->
        assumptions.forEachBool { variable, value -> array[variable] = (if (value) 1 else 0).toByte() }
    }
    private var epoch = 0
    private var end = 0

    var candidateCalls = 0L
        private set
    var rejected = 0L
        private set
    var cancelled = 0L
        private set
    var factVisits = 0L
        private set
    var edgeVisits = 0L
        private set
    var naryVisits = 0L
        private set
    var naryLiteralVisits = 0L
        private set
    var largestMove = 0
        private set

    private fun begin() {
        if (epoch == Int.MAX_VALUE) {
            stamps.fill(0)
            seedStamps.fill(0)
            epoch = 1
        } else epoch++
        end = 0
    }

    private fun force(literal: Int): Boolean {
        val variable = Lit.variable(literal)
        val value = Lit.isPositive(literal)
        if (frozen[variable].toInt() >= 0 && (frozen[variable].toInt() == 1) != value) return false
        if (stamps[variable] == epoch) return values[variable] == value
        stamps[variable] = epoch
        values[variable] = value
        queue[end++] = literal
        return true
    }

    private fun expand(cancellation: Cancellation): Boolean {
        var cursor = 0
        var visits = 0
        while (cursor < end) {
            if (cursor % 256 == 0 && cancellation.isCancelled()) return false
            val literal = queue[cursor++]
            factVisits++
            for (forced in graph.edges[literal]) {
                edgeVisits++
                if (visits++ % 256 == 0 && cancellation.isCancelled()) return false
                if (!force(forced)) return false
            }
            for (row in graph.naryOccurrences[literal]) {
                naryVisits++
                if (visits++ % 256 == 0 && cancellation.isCancelled()) return false
                if (!expandClause(graph.naryClauses[row], cancellation)) return false
            }
        }
        return !cancellation.isCancelled()
    }

    private fun expandClause(literals: IntArray, cancellation: Cancellation): Boolean {
        var unknown = 0
        var last = Lit.NONE
        for ((index, literal) in literals.withIndex()) {
            naryLiteralVisits++
            if (index % 256 == 0 && cancellation.isCancelled()) return false
            val variable = Lit.variable(literal)
            val value = if (stamps[variable] == epoch) {
                if (values[variable]) 1 else 0
            } else frozen[variable].toInt()
            if (value < 0) {
                unknown++
                last = literal
                if (unknown > 1) return true
            } else if (Lit.evaluate(literal, value == 1)) return true
        }
        return unknown == 1 && force(last)
    }

    fun seed(state: LocalSearchState, root: Int, desired: Boolean, cancellation: Cancellation): Boolean {
        check(state.assumptions === assumptions)
        begin()
        for ((index, literal) in graph.units.withIndex()) {
            if (index % 256 == 0 && cancellation.isCancelled()) return false
            if (!force(literal)) return false
        }
        if (!force(Lit.make(root, desired)) || !expand(cancellation)) return false
        for (i in 0 until end) {
            if (i % 256 == 0 && cancellation.isCancelled()) return false
            state.assignment.setBool(Lit.variable(queue[i]), Lit.isPositive(queue[i]))
        }
        return true
    }

    fun close(state: LocalSearchState, move: Move, cancellation: Cancellation): Move? {
        check(state.assumptions === assumptions)
        candidateCalls++
        begin()
        var seedCount = 0
        val parts = if (move is Move.Compound) move.parts else listOf(move)
        for ((index, part) in parts.withIndex()) {
            if (index % 256 == 0 && cancellation.isCancelled()) {
                cancelled++
                return null
            }
            require(part is Move.BoolFlip)
            val variable = part.varId
            if (seedStamps[variable] != epoch) {
                seedStamps[variable] = epoch
                seedParity[variable] = true
                seeds[seedCount++] = variable
            } else seedParity[variable] = !seedParity[variable]
        }
        var valid = true
        for ((index, literal) in graph.units.withIndex()) {
            if (index % 256 == 0 && cancellation.isCancelled()) {
                cancelled++
                return null
            }
            valid = force(literal) && valid
        }
        for (i in 0 until seedCount) {
            if (i % 256 == 0 && cancellation.isCancelled()) {
                cancelled++
                return null
            }
            val variable = seeds[i]
            if (seedParity[variable]) valid = force(Lit.make(variable, !state.assignment.boolValue(variable))) && valid
        }
        if (!valid || !expand(cancellation)) {
            if (cancellation.isCancelled()) cancelled++ else rejected++
            return null
        }
        val closed = ArrayList<Move>()
        for (i in 0 until end) {
            if (i % 256 == 0 && cancellation.isCancelled()) {
                cancelled++
                return null
            }
            val literal = queue[i]
            val variable = Lit.variable(literal)
            if (state.assignment.boolValue(variable) != Lit.isPositive(literal) &&
                state.invariants?.isDefinedBool(variable) != true
            ) closed.add(Move.BoolFlip(variable))
        }
        largestMove = maxOf(largestMove, closed.size)
        return when (closed.size) {
            0 -> null
            1 -> closed.single()
            else -> Move.Compound(closed)
        }
    }

    fun wrap(strategy: SourceDrivenStrategy, cancellation: Cancellation): SourceDrivenStrategy =
        wrapBooleanRepairs(strategy) { state, move -> close(state, move, cancellation) }
}

internal fun wrapBooleanRepairs(
    strategy: SourceDrivenStrategy,
    close: (LocalSearchState, Move) -> Move?,
): SourceDrivenStrategy = strategy.copy(
    sources = strategy.sources.map { configured ->
        val source = configured.source
        val wrapped = object : MoveSource by source {
            private val raw = MoveSink()

            override fun generate(state: LocalSearchState, sink: MoveSink) {
                raw.clear()
                raw.setAssumptions(state.assumptions)
                raw.setInvariants(state.invariants)
                raw.setOwners(state.seeding.ownerInt)
                source.generate(state, raw)
                for (move in raw.list) {
                    when (val closed = close(state, move)) {
                        is Move.BoolFlip -> sink.addBoolFlip(closed.varId)
                        is Move.Compound -> sink.addCompound(closed.parts)
                        null -> Unit
                        else -> error("Boolean closure returned a non-Boolean move")
                    }
                }
            }
        }
        ConfiguredSource(wrapped, configured.cap, configured.enabled, configured.stallGated, configured.phase)
    },
    perturbation = strategy.perturbation?.let { hook ->
        { state -> hook(state)?.let { close(state, it) } }
    },
)
