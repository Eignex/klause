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
    private val support: BooleanSupportRepairs?,
) {
    private var epoch = 0
    private var end = 0
    private var supportHead = 0
    private var supportTail = 0
    private var operationsBeforePoll = 0

    companion object {
        private const val CANCELLATION_BATCH = 256

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
            val support = if (clauses.isNotEmpty()) {
                val rows = IntArray(clauses.size + 1)
                yield(null)
                val clauseStamps = IntArray(clauses.size)
                yield(null)
                val breakStamps = IntArray(size)
                yield(null)
                val breakScores = IntArray(size)
                yield(null)
                val objectiveStamps = IntArray(size)
                yield(null)
                val objectiveScores = DoubleArray(size)
                yield(null)
                BooleanSupportRepairs(rows, clauseStamps, breakStamps, breakScores, objectiveStamps, objectiveScores)
            } else null
            @Suppress("UNCHECKED_CAST")
            yield(PreparedBooleanRepairs(BooleanRepairClosure(
                edges as Array<IntArray>, units.toIntArray(), clauses.toTypedArray(),
                occurrences as Array<IntArray>, frozen, stamps, values, queue,
                seedStamps, seedParity, seeds, inconsistent, support,
            )))
        }
    }

    fun beginScoringBatch() {
        support?.beginBatch()
    }

    fun closeInto(state: LocalSearchState, moves: List<Move>, sink: MoveSink, cancellation: Cancellation) {
        beginScoringBatch()
        for (move in moves) {
            if (cancellation.isCancelled()) return
            when (val closed = close(state, move, cancellation)) {
                is Move.BoolFlip -> sink.addBoolFlip(closed.varId)
                is Move.Compound -> sink.addCompound(closed.parts)
                null -> Unit
                else -> error("Boolean closure returned a non-Boolean move")
            }
        }
    }

    fun close(state: LocalSearchState, move: Move, cancellation: Cancellation): Move? {
        if (inconsistent) return null
        operationsBeforePoll = 0
        if (interrupted(cancellation)) return null
        if (epoch == Int.MAX_VALUE) {
            stamps.fill(0)
            seedStamps.fill(0)
            support?.clauseStamps?.fill(0)
            epoch = 1
        } else epoch++
        end = 0
        supportHead = 0
        supportTail = 0
        var seedCount = 0
        val parts = if (move is Move.Compound) move.parts else listOf(move)
        for (part in parts) {
            if (interrupted(cancellation)) return null
            if (part !is Move.BoolFlip) return move
            val variable = part.varId
            if (seedStamps[variable] != epoch) {
                seedStamps[variable] = epoch
                seedParity[variable] = true
                seeds[seedCount++] = variable
            } else seedParity[variable] = !seedParity[variable]
        }
        for (literal in units) {
            if (interrupted(cancellation)) return null
            if (!force(literal)) return null
        }
        for (index in 0 until seedCount) {
            if (interrupted(cancellation)) return null
            val variable = seeds[index]
            if (seedParity[variable] && !force(Lit.make(variable, !state.assignment.boolValue(variable)))) return null
        }
        val supportState = state.takeIf { support != null && it.cost == 0L }
        if (!expand(cancellation, supportState)) return null
        val closed = ArrayList<Move>()
        for (index in 0 until end) {
            if (interrupted(cancellation)) return null
            val literal = queue[index]
            val variable = Lit.variable(literal)
            if (state.assignment.boolValue(variable) != Lit.isPositive(literal) &&
                state.invariants?.isDefinedBool(variable) != true
            ) closed.add(Move.BoolFlip(variable))
        }
        val result = when (closed.size) {
            0 -> null
            1 -> closed.single()
            else -> Move.Compound(closed)
        }
        if (supportState != null && result != null) {
            if (cancellation.isCancelled()) return null
            if (state.netDelta(result) != 0L) return null
        }
        return result.takeUnless { cancellation.isCancelled() }
    }

    private fun interrupted(cancellation: Cancellation): Boolean {
        // Nested short clauses share the same bounded graph-visit window.
        if (operationsBeforePoll-- > 0) return false
        operationsBeforePoll = CANCELLATION_BATCH - 1
        return cancellation.isCancelled()
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

    private fun expand(cancellation: Cancellation, supportState: LocalSearchState?): Boolean {
        var cursor = 0
        val scratch = support
        while (cursor < end || supportHead != supportTail) {
            while (cursor < end) {
                if (interrupted(cancellation)) return false
                val literal = queue[cursor++]
                for (forced in edges[literal]) {
                    if (interrupted(cancellation)) return false
                    if (!force(forced)) return false
                }
                for (row in occurrences[literal]) {
                    if (interrupted(cancellation)) return false
                    if (!expandClause(clauses[row], cancellation, null)) return false
                    if (supportState != null && scratch != null && scratch.clauseStamps[row] != epoch) {
                        scratch.clauseStamps[row] = epoch
                        scratch.rows[supportTail] = row
                        supportTail++
                        if (supportTail == scratch.rows.size) supportTail = 0
                    }
                }
            }
            if (supportHead != supportTail && scratch != null) {
                if (interrupted(cancellation)) return false
                val row = scratch.rows[supportHead]
                supportHead++
                if (supportHead == scratch.rows.size) supportHead = 0
                scratch.clauseStamps[row] = 0
                if (!expandClause(clauses[row], cancellation, supportState)) return false
            }
        }
        return !cancellation.isCancelled()
    }

    // Unforced live values are not premises for a proposal's unit consequences.
    private fun expandClause(
        literals: IntArray, cancellation: Cancellation, supportState: LocalSearchState?,
    ): Boolean {
        var unknown = 0
        var last = Lit.NONE
        var supported = false
        for (literal in literals) {
            if (interrupted(cancellation)) return false
            val variable = Lit.variable(literal)
            val value = if (stamps[variable] == epoch) {
                if (values[variable]) 2 else 1
            } else frozen[variable].toInt()
            if (value == 0) {
                unknown++
                last = literal
                if (supportState != null && Lit.evaluate(literal, supportState.assignment.boolValue(variable))) {
                    supported = true
                }
                if (unknown > 1 && supportState == null) return true
            } else if (Lit.evaluate(literal, value == 2)) return true
        }
        if (unknown < 2) return unknown == 1 && force(last)
        if (supportState == null || supported) return true
        val scratch = checkNotNull(support)
        var chosen = Lit.NONE
        var bestBreak = Int.MAX_VALUE
        var bestObjective = Double.POSITIVE_INFINITY
        for (literal in literals) {
            if (interrupted(cancellation)) return false
            val variable = Lit.variable(literal)
            if (stamps[variable] == epoch || frozen[variable].toInt() != 0 ||
                supportState.invariants?.isDefinedBool(variable) == true
            ) continue
            if (cancellation.isCancelled()) return false
            val score = scratch.breakScore(supportState, variable)
            if (score > bestBreak) continue
            if (cancellation.isCancelled()) return false
            val objectiveDelta = scratch.objectiveDelta(supportState, variable)
            if (chosen == Lit.NONE || score < bestBreak || objectiveDelta < bestObjective) {
                chosen = literal
                bestBreak = score
                bestObjective = objectiveDelta
            }
        }
        if (chosen == Lit.NONE) return false
        // A replacement supporter is a heuristic choice, not an entailed literal.
        return force(chosen)
    }
}

private class BooleanSupportRepairs(
    val rows: IntArray,
    val clauseStamps: IntArray,
    private val breakStamps: IntArray,
    private val breakScores: IntArray,
    private val objectiveStamps: IntArray,
    private val objectiveScores: DoubleArray,
) {
    private var epoch = 0

    fun beginBatch() {
        // Reversible grading sees one live assignment throughout a source's proposal batch.
        if (epoch == Int.MAX_VALUE) {
            breakStamps.fill(0)
            objectiveStamps.fill(0)
            epoch = 1
        } else epoch++
    }

    fun breakScore(state: LocalSearchState, variable: Int): Int {
        if (epoch == 0 || breakStamps[variable] != epoch) {
            breakScores[variable] = state.breakScore(Move.BoolFlip(variable))
            breakStamps[variable] = epoch
        }
        return breakScores[variable]
    }

    fun objectiveDelta(state: LocalSearchState, variable: Int): Double {
        if (epoch == 0 || objectiveStamps[variable] != epoch) {
            objectiveScores[variable] = state.shaping.objective?.let {
                state.objectiveDelta(it, Move.BoolFlip(variable))
            } ?: 0.0
            objectiveStamps[variable] = epoch
        }
        return objectiveScores[variable]
    }
}
