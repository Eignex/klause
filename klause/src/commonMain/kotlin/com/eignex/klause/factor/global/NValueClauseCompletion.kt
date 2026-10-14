package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Assignment
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.negate
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal class NValueClauseCompletion(
    private val state: LocalSearchState,
    private val sink: MoveSink,
    private val countedInts: IntArray,
    private val count: Int,
) {
    private val protectedInts = countedInts + intArrayOf(count)
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
    private val writtenInts = IntHashSet()
    private val receivingClasses = LongHashSet()
    private val pending = ArrayList<Int>()
    private val parts = ArrayList<Move>()
    private var foldVisits = 0
    private data class Predicate(val variable: Int, val value: Long)
    private val predicates = HashMap<ReifiedLinear, Predicate?>()

    fun complete(move: Move): Move {
        pinnedInts.clear()
        protectedInts.forEach { pinnedInts.add(it) }
        pinnedBools.clear()
        writtenInts.clear()
        receivingClasses.clear()
        pending.clear()
        parts.clear()
        foldVisits = 0
        state.assignment.copyInto(assignment)
        val initial = if (move is Move.Compound) move.parts else listOf(move)
        for (part in initial) {
            if (part is Move.IntSet && part.varId != count && part.varId in countedInts) {
                receivingClasses.add(part.newValue)
            }
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

                is Xor -> xorRepairs(factor)

                is Element -> elementRepairs(factor)

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
        return if (parts.size == 1) parts[0] else Move.Compound(parts.toList())
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
        val predicate = predicate(row) ?: return emptyList()
        val equal = assignment.intValue(predicate.variable) == predicate.value
        val actual = if (row.op == LinearOp.EQ) equal else !equal
        val desired = assignment.boolValue(row.auxBoolVar)
        if (actual == desired) return emptyList()
        val integer = predicateRepair(row, desired)
        val indicator = if (row.auxBoolVar !in pinnedBools && sink.allowsBool(row.auxBoolVar)) {
            Move.BoolFlip(row.auxBoolVar)
        } else {
            null
        }
        return listOfNotNull(integer, indicator)
    }

    private fun xorRepairs(factor: Xor): List<Move> {
        var parity = 0
        val odd = IntHashSet()
        for (literal in factor.literals) {
            if (holds(literal)) parity = parity xor 1
            val b = Lit.variable(literal)
            if (!odd.add(b)) odd.remove(b)
        }
        if (parity == factor.targetParity) return emptyList()
        return factor.boolVars.filter { it in odd }.map {
            repair(Lit.make(it, !assignment.boolValue(it)))
        }.filterNotNull().distinct()
    }

    private fun elementRepairs(element: Element): List<Move> {
        val position = assignment.intValue(element.idx) - element.indexOffset
        if (position < 0 || position >= element.arr.size) return emptyList()
        val entry = element.arr[position.toInt()]
        val value = if (element.arrIsVars) assignment.intValue(entry.toInt()) else entry
        val result = assignment.intValue(element.result)
        if (value == result) return emptyList()
        val repairs = ArrayList<Move>()
        if (element.arrIsVars) {
            val cell = entry.toInt()
            if (cell !in pinnedInts && sink.allowsInt(cell) && result in state.rootDomains[cell]) {
                repairs += Move.IntSet(cell, result)
            }
        }
        if (element.result !in pinnedInts && sink.allowsInt(element.result) &&
            value in state.rootDomains[element.result]
        ) {
            repairs += Move.IntSet(element.result, value)
        }
        val index = element.idx
        if (index != count && index in countedInts && index !in writtenInts &&
            assignment.intValue(index) in receivingClasses && sink.allowsInt(index)
        ) {
            retargetIndex(index)?.let { repairs += Move.IntSet(index, it) }
        }
        return repairs
    }

    private fun retargetIndex(index: Int): Long? {
        val occurrences = state.projection.intOccurrences[index]
        if (occurrences.size > MAX_INDEX_OCCURRENCES) return null
        val elements = ArrayList<Element>()
        for (fid in occurrences) {
            val element = state.problem.factors[fid] as? Element ?: continue
            if (element.idx == index) elements += element
        }
        val seen = LongHashSet()
        var target: Long? = null
        for (i in 0 until minOf(countedInts.size, MAX_INDEX_POSITIONS)) {
            val variable = countedInts[i]
            if (variable == count) continue
            val candidate = assignment.intValue(variable)
            if (candidate == assignment.intValue(index) || !seen.add(candidate)) continue
            if (seen.size > MAX_TARGET_CLASSES) break
            if (candidate !in state.rootDomains[index] || elements.any { !admitsIndex(it, candidate) }) continue
            if (target == null || candidate < target) target = candidate
        }
        return target
    }

    private fun admitsIndex(element: Element, index: Long): Boolean {
        val position = index - element.indexOffset
        if (position < 0 || position >= element.arr.size) return false
        val entry = element.arr[position.toInt()]
        val value = if (element.arrIsVars) assignment.intValue(entry.toInt()) else entry
        if (value !in state.rootDomains[element.result]) return false
        val maintained = network.elementResultDefinition(element.result)?.factor === element &&
            state.moveSink.allowsDefinedInt(element.result) && sink.allowsDefinedInt(element.result)
        return maintained || value == assignment.intValue(element.result)
    }

    private fun predicateRepair(row: ReifiedLinear, desired: Boolean): Move.IntSet? {
        if (!((row.op == LinearOp.EQ && desired) || (row.op == LinearOp.NE && !desired))) return null
        val predicate = predicate(row) ?: return null
        val value = predicate.value
        val variable = predicate.variable
        if (variable in pinnedInts || !sink.allowsInt(variable) || value !in state.rootDomains[variable] ||
            value == assignment.intValue(variable)
        ) {
            return null
        }
        return Move.IntSet(variable, value)
    }

    private fun predicate(row: ReifiedLinear): Predicate? {
        if (predicates.containsKey(row)) return predicates[row]
        return normalize(row).also { predicates[row] = it }
    }

    private fun normalize(row: ReifiedLinear): Predicate? {
        if (row.vars.size > MAX_PREDICATE_TERMS || (row.op != LinearOp.EQ && row.op != LinearOp.NE)) return null
        val constants = row.integerConstants ?: return null
        var variable = -1
        var coefficient = 0L
        var bound = bigIntOf(constants.bound)
        for (i in row.vars.indices) {
            val domain = state.rootDomains[row.vars[i]]
            if (domain.min == domain.max) {
                if (domain.min != 0L) bound -= bigIntOf(constants.coeff(i)) * bigIntOf(domain.min)
            } else {
                if (variable >= 0) return null
                variable = row.vars[i]
                coefficient = constants.coeff(i)
            }
        }
        if (variable < 0 || (coefficient != 1L && coefficient != -1L)) return null
        val value = if (coefficient == 1L) bound else bound.negate()
        return if (value.fitsLong()) Predicate(variable, value.toLongExact()) else null
    }

    private fun pin(move: Move) {
        when (move) {
            is Move.IntSet -> {
                pinnedInts.add(move.varId)
                writtenInts.add(move.varId)
            }

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
            val frozen = if (node.outIsBool) {
                state.assumptions.isFrozenBool(node.out)
            } else {
                !state.moveSink.allowsDefinedInt(node.out)
            }
            if (frozen) continue
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
        if (factor is Clause || factor is Xor || factor is Element ||
            (factor is ReifiedLinear && predicate(factor) != null)
        ) {
            pending += fid
        }
    }

    private fun holds(literal: Int): Boolean = Lit.evaluate(literal, assignment.boolValue(Lit.variable(literal)))

    private companion object {
        const val MAX_FACTOR_VISITS = 512
        const val MAX_WRITES = 32
        const val MAX_FOLD_DEPTH = 4
        const val MAX_FOLD_VISITS = 32
        const val MAX_PREDICATE_TERMS = 32
        const val MAX_INDEX_OCCURRENCES = 256
        const val MAX_TARGET_CLASSES = 32
        const val MAX_INDEX_POSITIONS = 256
    }
}
