package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Assignment
import com.eignex.klause.util.IntHashSet

internal class NValueClauseCompletion(
    private val state: LocalSearchState,
    private val sink: MoveSink,
    protectedInts: IntArray,
) {
    private val network = checkNotNull(state.invariants)
    private val assignment = Assignment(state.problem.numBoolVars, state.problem.numIntVars, state.problem.numRealVars)
    private val pinnedInts = IntHashSet().also { pins -> protectedInts.forEach { pins.add(it) } }
    private val pinnedBools = IntHashSet()
    private val pending = ArrayList<Int>()
    private val parts = ArrayList<Move>()

    fun complete(move: Move): Move {
        state.assignment.copyInto(assignment)
        val initial = if (move is Move.Compound) move.parts else listOf(move)
        for (part in initial) {
            parts += part
            pin(part)
            write(part)
        }
        propagate(initial)
        var position = 0
        var added = 0
        while (position < pending.size && position < MAX_CLAUSE_VISITS && added < MAX_WRITES) {
            val clause = state.problem.factors[pending[position++]] as Clause
            if (clause.literals.any { holds(it) }) continue
            val choices = clause.literals.map(::repair).filterNotNull().distinct()
            if (choices.size != 1) continue
            val next = choices[0]
            parts += next
            pin(next)
            write(next)
            propagate(listOf(next))
            added++
        }
        return if (parts.size == 1) parts[0] else Move.Compound(parts)
    }

    private fun repair(literal: Int): Move? {
        val b = Lit.variable(literal)
        val desired = Lit.isPositive(literal)
        if (state.assumptions.isFrozenBool(b) || b in pinnedBools) return null
        if (!network.isDefinedBool(b)) return if (sink.allowsBool(b)) Move.BoolFlip(b) else null
        for (fid in state.projection.boolOccurrences[b]) {
            val row = state.problem.factors[fid] as? ReifiedLinear ?: continue
            if (row.auxBoolVar != b || row.vars.size != 1 ||
                !((row.op == LinearOp.EQ && desired) || (row.op == LinearOp.NE && !desired))
            ) continue
            val constants = row.integerConstants ?: continue
            val coefficient = constants.coeff(0)
            val value = when {
                coefficient == 1L -> constants.bound
                coefficient == -1L && constants.bound != Long.MIN_VALUE -> -constants.bound
                else -> continue
            }
            val variable = row.vars[0]
            if (variable in pinnedInts || !sink.allowsInt(variable) || value !in state.rootDomains[variable] ||
                value == assignment.intValue(variable)
            ) continue
            return Move.IntSet(variable, value)
        }
        return null
    }

    private fun pin(move: Move) {
        when (move) {
            is Move.IntSet -> pinnedInts.add(move.varId)
            is Move.BoolFlip -> pinnedBools.add(move.varId)
            else -> Unit
        }
    }

    private fun write(move: Move) {
        when (move) {
            is Move.IntSet -> assignment.setInt(move.varId, move.newValue)
            is Move.BoolFlip -> {
                assignment.flipBool(move.varId)
                enqueue(move.varId)
            }
            is Move.RealSet -> assignment.setReal(move.varId, move.newValue)
            is Move.Compound -> error("Completion parts must be primitive")
        }
    }

    private fun propagate(moves: List<Move>) {
        val ints = moves.filterIsInstance<Move.IntSet>().map { it.varId }.toIntArray()
        val bools = moves.filterIsInstance<Move.BoolFlip>().map { it.varId }.toIntArray()
        for (index in network.affectedNodes(ints, bools, assignment)) {
            val node = network.node(index)
            if (if (node.outIsBool) state.assumptions.isFrozenBool(node.out)
                else !state.moveSink.allowsDefinedInt(node.out)
            ) continue
            val value = node.eval(assignment, state.rootDomains)
            if (value == DefinitionalSweep.SweepNode.NO_WRITE) continue
            if (node.outIsBool) {
                if (assignment.boolValue(node.out) != (value != 0L)) {
                    assignment.setBool(node.out, value != 0L)
                    enqueue(node.out)
                }
            } else {
                assignment.setInt(node.out, value)
            }
        }
    }

    private fun enqueue(boolVar: Int) {
        for (fid in state.projection.boolOccurrences[boolVar]) {
            if (pending.size >= MAX_CLAUSE_VISITS) return
            if (state.problem.factors[fid] is Clause) pending += fid
        }
    }

    private fun holds(literal: Int): Boolean = Lit.evaluate(literal, assignment.boolValue(Lit.variable(literal)))

    private companion object {
        const val MAX_CLAUSE_VISITS = 512
        const val MAX_WRITES = 32
    }
}
