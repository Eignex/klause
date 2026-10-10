package com.eignex.klause.localsearch

import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal class AffineIndexRepair(
    private val state: LocalSearchState,
    private val sink: MoveSink,
    private val definition: FunctionalObjective.Lin,
) {
    fun propose(value: Long): Boolean {
        val inputs = definition.ins
        if (inputs.size !in 1..2 || inputs.any { it.varId < 0 }) return false
        if (inputs.size == 2 && inputs[0].varId == inputs[1].varId) return false
        if (definition.outCoeff != 1L && definition.outCoeff != -1L) return false
        val rhs = targetRhs(value) ?: return false
        for (i in inputs.indices) {
            val otherValue = if (inputs.size == 2) current(1 - i) else 0L
            val candidate = solve(i, otherValue, rhs) ?: continue
            val first = if (i == 0) candidate else otherValue
            val second = if (i == 1) candidate else otherValue
            if (queue(first, second)) return true
        }
        return inputs.size == 2 && proposePair(rhs)
    }

    private fun proposePair(rhs: Long): Boolean {
        // Enumerate present members only, with a fixed cap independent of the declared span.
        val firstSpan = state.rootDomains[definition.ins[0].varId].spanOrNull(MAX_COORDINATE_VALUES)
        val secondSpan = state.rootDomains[definition.ins[1].varId].spanOrNull(MAX_COORDINATE_VALUES)
        val i = if (firstSpan != null && (secondSpan == null || firstSpan.size <= secondSpan.size)) 0 else 1
        val span = (if (i == 0) firstSpan else secondSpan) ?: return false
        for (k in 0 until span.size) {
            val member = span.valueAt(k)
            val other = solve(1 - i, member, rhs) ?: continue
            if (queue(if (i == 0) member else other, if (i == 1) member else other)) return true
        }
        return false
    }

    private fun current(i: Int): Long = state.assignment.intValue(definition.ins[i].varId)

    @Suppress("SwallowedException")
    private fun targetRhs(value: Long): Long? = try {
        subExact(definition.c, mulExact(definition.outCoeff, value))
    } catch (_: CheckedLongOverflowException) {
        null
    }

    @Suppress("SwallowedException")
    private fun solve(i: Int, otherValue: Long, rhs: Long): Long? = try {
        val coefficient = definition.coeffs[i]
        val numerator = if (definition.ins.size == 1) rhs else {
            subExact(rhs, mulExact(definition.coeffs[1 - i], otherValue))
        }
        if (coefficient == 0L || numerator % coefficient != 0L ||
            (numerator == Long.MIN_VALUE && coefficient == -1L)
        ) null else numerator / coefficient
    } catch (_: CheckedLongOverflowException) {
        null
    }

    private fun queue(first: Long, second: Long): Boolean {
        val values = longArrayOf(first, second)
        val targets = LinkedHashMap<Int, Long>()
        for (i in definition.ins.indices) {
            if (!coordinateTarget(definition.ins[i].varId, values[i], targets, 0)) return false
        }
        val coordinates = targets.filter { (v, value) -> value != state.assignment.intValue(v) }
            .map { (v, value) -> Move.IntSet(v, value) }
        if (coordinates.isEmpty()) return false
        val parts = ArrayList<Move>(coordinates.size + 2)
        parts.addAll(coordinates)
        for (coordinate in coordinates) {
            val channeling = state.synthesizeChannelingMove(coordinate.varId, coordinate.newValue)
            // Counter-shifts can overwrite a requested coordinate; only equality indicators join
            // this repair. The definition network maintains its outputs after the joint move.
            if (channeling is Move.Compound) parts.addAll(channeling.parts.filterIsInstance<Move.BoolFlip>())
        }
        val before = sink.size
        sink.addCompound(parts.distinct())
        return sink.size > before
    }

    private fun coordinateTarget(varId: Int, value: Long, targets: MutableMap<Int, Long>, depth: Int): Boolean {
        if (value !in state.rootDomains[varId] || depth > MAX_ALIAS_DEPTH) return false
        val alias = state.invariants?.linearDefinition(varId)
        if (alias != null) {
            if (value != state.assignment.intValue(varId) && !sink.allowsDefinedInt(varId)) return false
            if (alias.ins.size != 1 || alias.ins[0].varId < 0 ||
                (alias.outCoeff != 1L && alias.outCoeff != -1L)
            ) return false
            val target = aliasInput(alias, value) ?: return false
            return coordinateTarget(alias.ins[0].varId, target, targets, depth + 1)
        }
        if (value != state.assignment.intValue(varId) && !sink.allowsInt(varId)) return false
        if (targets.containsKey(varId) && targets[varId] != value) return false
        targets[varId] = value
        return true
    }

    @Suppress("SwallowedException")
    private fun aliasInput(alias: FunctionalObjective.Lin, value: Long): Long? = try {
        val numerator = subExact(alias.c, mulExact(alias.outCoeff, value))
        val coefficient = alias.coeffs[0]
        if (coefficient == 0L || numerator % coefficient != 0L ||
            (numerator == Long.MIN_VALUE && coefficient == -1L)
        ) null else numerator / coefficient
    } catch (_: CheckedLongOverflowException) {
        null
    }

    private companion object {
        const val MAX_COORDINATE_VALUES = 64L
        const val MAX_ALIAS_DEPTH = 16
    }
}
