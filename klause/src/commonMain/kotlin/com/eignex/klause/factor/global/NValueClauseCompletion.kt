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
import com.eignex.klause.util.MutableIntObjectMap

internal class NValueClauseCompletion(
    private val state: LocalSearchState,
    private val sink: MoveSink,
    protectedInts: IntArray,
) {
    private val network = checkNotNull(state.invariants)
    private val folds = MutableIntObjectMap<DefinitionalSweep.SweepNode.BoolFold>().also { index ->
        for (i in 0 until network.size) {
            val fold = network.node(i) as? DefinitionalSweep.SweepNode.BoolFold ?: continue
            index.put(fold.out, fold)
        }
    }
    private val assignment = Assignment(state.problem.numBoolVars, state.problem.numIntVars, state.problem.numRealVars)
    private val pinnedInts = IntHashSet().also { pins -> protectedInts.forEach { pins.add(it) } }
    private val pinnedBools = IntHashSet()
    private val pending = ArrayList<Int>()
    private val parts = ArrayList<Move>()
    private var foldVisits = 0

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
        while (position < pending.size && position < MAX_FACTOR_VISITS && added < MAX_WRITES) {
            val choices = when (val factor = state.problem.factors[pending[position++]]) {
                is Clause -> {
                    if (factor.literals.any { holds(it) }) continue
                    factor.literals.map(::repair).filterNotNull().distinct()
                }
                is ReifiedLinear -> channelRepairs(factor)
                else -> emptyList()
            }
            if (choices.size != 1) continue
            val next = choices[0]
            val writes = if (next is Move.Compound) next.parts else listOf(next)
            if (added + writes.size > MAX_WRITES) continue
            for (part in writes) {
                parts += part
                pin(part)
                write(part)
            }
            propagate(writes)
            added += writes.size
        }
        return if (parts.size == 1) parts[0] else Move.Compound(parts)
    }

    private fun repair(literal: Int): Move? = repair(literal, 0)

    private fun repair(literal: Int, depth: Int): Move? {
        val b = Lit.variable(literal)
        val desired = Lit.isPositive(literal)
        if (state.assumptions.isFrozenBool(b) || b in pinnedBools) return null
        if (!network.isDefinedBool(b)) return if (sink.allowsBool(b)) Move.BoolFlip(b) else null
        for (fid in state.projection.boolOccurrences[b]) {
            val row = state.problem.factors[fid] as? ReifiedLinear ?: continue
            if (row.auxBoolVar == b) predicateRepair(row, desired)?.let { return it }
        }
        if (depth >= MAX_FOLD_DEPTH || foldVisits >= MAX_FOLD_VISITS) return null
        val fold = folds[b] ?: return null
        if (fold.isAnd != desired || fold.ins.size > MAX_WRITES) return null
        foldVisits++
        val writes = ArrayList<Move>()
        for (input in fold.ins) {
            val required = if (desired) input else Lit.negate(input)
            if (holds(required)) continue
            val child = repair(required, depth + 1) ?: return null
            writes += if (child is Move.Compound) child.parts else listOf(child)
            if (writes.size > MAX_WRITES) return null
        }
        val distinct = writes.distinct()
        val ints = distinct.filterIsInstance<Move.IntSet>()
        if (ints.map { it.varId }.distinct().size != ints.size || distinct.isEmpty()) return null
        return if (distinct.size == 1) distinct[0] else Move.Compound(distinct)
    }

    private fun channelRepairs(row: ReifiedLinear): List<Move> {
        val value = predicateValue(row) ?: return emptyList()
        val equal = assignment.intValue(row.vars[0]) == value
        val actual = if (row.op == LinearOp.EQ) equal else !equal
        val desired = assignment.boolValue(row.auxBoolVar)
        if (actual == desired) return emptyList()
        val integer = predicateRepair(row, desired)
        val indicator = if (row.auxBoolVar !in pinnedBools && sink.allowsBool(row.auxBoolVar)) {
            Move.BoolFlip(row.auxBoolVar)
        } else null
        return listOfNotNull(integer, indicator)
    }

    private fun predicateRepair(row: ReifiedLinear, desired: Boolean): Move.IntSet? {
        if (!((row.op == LinearOp.EQ && desired) || (row.op == LinearOp.NE && !desired))) return null
        val value = predicateValue(row) ?: return null
        val variable = row.vars[0]
        if (variable in pinnedInts || !sink.allowsInt(variable) || value !in state.rootDomains[variable] ||
            value == assignment.intValue(variable)
        ) return null
        return Move.IntSet(variable, value)
    }

    private fun predicateValue(row: ReifiedLinear): Long? {
        if (row.vars.size != 1 || (row.op != LinearOp.EQ && row.op != LinearOp.NE)) return null
        val constants = row.integerConstants ?: return null
        return when {
            constants.coeff(0) == 1L -> constants.bound
            constants.coeff(0) == -1L && constants.bound != Long.MIN_VALUE -> -constants.bound
            else -> null
        }
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
            is Move.IntSet -> {
                assignment.setInt(move.varId, move.newValue)
                for (fid in state.projection.intOccurrences[move.varId]) enqueueFactor(fid)
            }
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
        for (fid in state.projection.boolOccurrences[boolVar]) enqueueFactor(fid)
    }

    private fun enqueueFactor(fid: Int) {
        if (pending.size >= MAX_FACTOR_VISITS) return
        val factor = state.problem.factors[fid]
        if (factor is Clause || (factor is ReifiedLinear && predicateValue(factor) != null)) pending += fid
    }

    private fun holds(literal: Int): Boolean = Lit.evaluate(literal, assignment.boolValue(Lit.variable(literal)))

    private companion object {
        const val MAX_FACTOR_VISITS = 512
        const val MAX_WRITES = 32
        const val MAX_FOLD_DEPTH = 4
        const val MAX_FOLD_VISITS = 32
    }
}
