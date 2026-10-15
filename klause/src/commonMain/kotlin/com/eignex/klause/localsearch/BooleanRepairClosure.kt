package com.eignex.klause.localsearch

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.Cancellation

internal class PreparedBooleanRepairs(val closure: BooleanRepairClosure?)

internal class BooleanRepairClosure private constructor(
    private val edges: Array<IntArray>,
    private val units: IntArray,
    private val clauses: Array<IntArray>,
    private val occurrences: Array<IntArray>,
    private val frozen: ByteArray,
    private val stamps: IntArray,
    private val values: BooleanArray,
    private val queue: IntArray,
    private val seedStamps: IntArray,
    private val seedParity: BooleanArray,
    private val seeds: IntArray,
    private val inconsistent: Boolean,
) {
    private var epoch = 0
    private var end = 0

    companion object {
        fun preparation(state: LocalSearchState): Sequence<PreparedBooleanRepairs?> = sequence {
            val problem = state.problem
            if (problem.numIntVars != 0 || problem.numRealVars != 0 ||
                state.invariants?.hasBooleanLiterals != true || problem.numBoolVars > Int.MAX_VALUE / 2
            ) {
                yield(PreparedBooleanRepairs(null))
                return@sequence
            }
            for ((index, factor) in problem.factors.withIndex()) {
                if (factor !is Clause) {
                    yield(PreparedBooleanRepairs(null))
                    return@sequence
                }
                if ((index + 1) % 256 == 0) yield(null)
            }
            val size = problem.numBoolVars
            val frozen = ByteArray(size)
            yield(null)
            for (variable in 0 until size) {
                state.assumptions.boolValueOrNull(variable)?.let {
                    frozen[variable] = (if (it) 2 else 1).toByte()
                }
                if ((variable + 1) % 256 == 0) yield(null)
            }
            val edgeLists = arrayOfNulls<MutableList<Int>>(size * 2)
            yield(null)
            for (literal in edgeLists.indices) {
                edgeLists[literal] = ArrayList()
                if ((literal + 1) % 256 == 0) yield(null)
            }
            val occurrenceLists = arrayOfNulls<MutableList<Int>>(size * 2)
            yield(null)
            for (literal in occurrenceLists.indices) {
                occurrenceLists[literal] = ArrayList()
                if ((literal + 1) % 256 == 0) yield(null)
            }
            val units = ArrayList<Int>()
            val clauses = ArrayList<IntArray>()
            var inconsistent = false
            var visits = 0
            for ((index, factor) in problem.factors.withIndex()) {
                val residual = ArrayList<Int>()
                var satisfied = false
                for (literal in (factor as Clause).literals) {
                    val pinned = frozen[Lit.variable(literal)].toInt()
                    if (pinned == 0) residual.add(literal) else if (Lit.evaluate(literal, pinned == 2)) {
                        satisfied = true
                        break
                    }
                    if (++visits % 256 == 0) yield(null)
                }
                if (!satisfied) when (residual.size) {
                    0 -> inconsistent = true
                    1 -> units.add(residual[0])
                    2 -> {
                        checkNotNull(edgeLists[Lit.negate(residual[0])]).add(residual[1])
                        checkNotNull(edgeLists[Lit.negate(residual[1])]).add(residual[0])
                    }
                    else -> {
                        val row = clauses.size
                        clauses.add(residual.toIntArray())
                        for (literal in residual) {
                            checkNotNull(occurrenceLists[Lit.negate(literal)]).add(row)
                            if (++visits % 256 == 0) yield(null)
                        }
                    }
                }
                if ((index + 1) % 256 == 0) yield(null)
            }
            val edges = arrayOfNulls<IntArray>(edgeLists.size)
            yield(null)
            for (literal in edges.indices) {
                edges[literal] = checkNotNull(edgeLists[literal]).toIntArray()
                if ((literal + 1) % 256 == 0) yield(null)
            }
            val occurrences = arrayOfNulls<IntArray>(occurrenceLists.size)
            yield(null)
            for (literal in occurrences.indices) {
                occurrences[literal] = checkNotNull(occurrenceLists[literal]).toIntArray()
                if ((literal + 1) % 256 == 0) yield(null)
            }
            val stamps = IntArray(size)
            yield(null)
            val values = BooleanArray(size)
            yield(null)
            val queue = IntArray(size)
            yield(null)
            val seedStamps = IntArray(size)
            yield(null)
            val seedParity = BooleanArray(size)
            yield(null)
            val seeds = IntArray(size)
            yield(null)
            @Suppress("UNCHECKED_CAST")
            yield(PreparedBooleanRepairs(BooleanRepairClosure(
                edges as Array<IntArray>, units.toIntArray(), clauses.toTypedArray(),
                occurrences as Array<IntArray>, frozen, stamps, values, queue,
                seedStamps, seedParity, seeds, inconsistent,
            )))
        }
    }

    fun closeAll(state: LocalSearchState, moves: List<Move>, cancellation: Cancellation): List<Move> {
        if (moves.isEmpty()) return emptyList()
        val result = ArrayList<Move>(moves.size)
        val compounds = ArrayList<Move>()
        for (move in moves) {
            if (cancellation.isCancelled()) return emptyList()
            when (val closed = close(state, move, cancellation)) {
                is Move.Compound -> compounds.add(closed)
                null -> Unit
                else -> result.add(closed)
            }
        }
        // MoveSink exposes primitive lanes before its compound side storage.
        result.addAll(compounds)
        return result
    }

    fun close(state: LocalSearchState, move: Move, cancellation: Cancellation): Move? {
        if (inconsistent) return null
        if (epoch == Int.MAX_VALUE) {
            stamps.fill(0)
            seedStamps.fill(0)
            epoch = 1
        } else epoch++
        end = 0
        var seedCount = 0
        val parts = if (move is Move.Compound) move.parts else listOf(move)
        for ((index, part) in parts.withIndex()) {
            if (index % 256 == 0 && cancellation.isCancelled()) return null
            if (part !is Move.BoolFlip) return move
            val variable = part.varId
            if (seedStamps[variable] != epoch) {
                seedStamps[variable] = epoch
                seedParity[variable] = true
                seeds[seedCount++] = variable
            } else seedParity[variable] = !seedParity[variable]
        }
        for ((index, literal) in units.withIndex()) {
            if (index % 256 == 0 && cancellation.isCancelled()) return null
            if (!force(literal)) return null
        }
        for (index in 0 until seedCount) {
            if (index % 256 == 0 && cancellation.isCancelled()) return null
            val variable = seeds[index]
            if (seedParity[variable] && !force(Lit.make(variable, !state.assignment.boolValue(variable)))) return null
        }
        if (!expand(cancellation)) return null
        val closed = ArrayList<Move>()
        for (index in 0 until end) {
            if (index % 256 == 0 && cancellation.isCancelled()) return null
            val literal = queue[index]
            val variable = Lit.variable(literal)
            if (state.assignment.boolValue(variable) != Lit.isPositive(literal) &&
                state.invariants?.isDefinedBool(variable) != true
            ) closed.add(Move.BoolFlip(variable))
        }
        return when (closed.size) {
            0 -> null
            1 -> closed.single()
            else -> Move.Compound(closed)
        }
    }

    private fun force(literal: Int): Boolean {
        val variable = Lit.variable(literal)
        val value = Lit.isPositive(literal)
        val pinned = frozen[variable].toInt()
        if (pinned != 0 && (pinned == 2) != value) return false
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
            for (forced in edges[literal]) {
                if (visits++ % 256 == 0 && cancellation.isCancelled()) return false
                if (!force(forced)) return false
            }
            for (row in occurrences[literal]) {
                if (visits++ % 256 == 0 && cancellation.isCancelled()) return false
                if (!expandClause(clauses[row], cancellation)) return false
            }
        }
        return !cancellation.isCancelled()
    }

    // Unforced live values are not premises for a proposal's unit consequences.
    private fun expandClause(literals: IntArray, cancellation: Cancellation): Boolean {
        var unknown = 0
        var last = Lit.NONE
        for ((index, literal) in literals.withIndex()) {
            if (index % 256 == 0 && cancellation.isCancelled()) return false
            val variable = Lit.variable(literal)
            val value = if (stamps[variable] == epoch) {
                if (values[variable]) 2 else 1
            } else frozen[variable].toInt()
            if (value == 0) {
                unknown++
                last = literal
                if (unknown > 1) return true
            } else if (Lit.evaluate(literal, value == 2)) return true
        }
        return unknown == 1 && force(last)
    }
}
