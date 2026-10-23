package com.eignex.klause.factor.global

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList

internal class AllDifferentTuplesInvariant(private val tuples: List<IntArray>) : Invariant {
    private val rowsByVariable: Map<Int, IntArray> = run {
        val rows = HashMap<Int, IntArrayList>()
        for ((index, tuple) in tuples.withIndex()) {
            for (variable in tuple) {
                val occurrences = rows.getOrPut(variable) { IntArrayList() }
                if (occurrences.isEmpty() || occurrences[occurrences.size - 1] != index) occurrences.add(index)
            }
        }
        rows.mapValues { it.value.toIntArray() }
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = HashMap<TupleValues, Int>()
        val keys = Array(tuples.size) { key(state, it) }
        val payload = TupleState(keys, counts)
        for (tuple in keys) payload.excess += adjust(counts, tuple, 1)
        state.refPayload[factorId] = payload
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        (state.refPayload[factorId] as TupleState).excess != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        compressViolation((state.refPayload[factorId] as TupleState).excess.toLong(), state.violationSoftCap)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int {
        val rows = rowsByVariable[intVar] ?: return 0
        if (state.assignment.intValue(intVar) == newValue) return 0
        val payload = state.refPayload[factorId] as TupleState
        val replacements = Array(rows.size) { key(state, rows[it], intVar, newValue) }
        var delta = 0
        for (row in rows) delta += adjust(payload.counts, payload.keys[row], -1)
        for (replacement in replacements) delta += adjust(payload.counts, replacement, 1)
        for (replacement in replacements) adjust(payload.counts, replacement, -1)
        for (row in rows) adjust(payload.counts, payload.keys[row], 1)
        return compressViolation((payload.excess + delta).toLong(), state.violationSoftCap) -
            compressViolation(payload.excess.toLong(), state.violationSoftCap)
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int {
        val rows = rowsByVariable[intVar] ?: return 0
        val payload = state.refPayload[factorId] as TupleState
        for (row in rows) payload.excess += adjust(payload.counts, payload.keys[row], -1)
        for (row in rows) {
            val replacement = key(state, row)
            payload.keys[row] = replacement
            payload.excess += adjust(payload.counts, replacement, 1)
        }
        return 0
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val payload = state.refPayload[factorId] as TupleState
        if (payload.excess == 0) return
        val start = state.rng.nextInt(tuples.size)
        for (offset in tuples.indices) {
            val row = (start + offset) % tuples.size
            if (payload.counts.getValue(payload.keys[row]) < 2) continue
            var emitted = 0
            for (variable in tuples[row]) {
                val current = state.assignment.intValue(variable)
                val domain = state.rootDomains[variable]
                if (current < domain.max) {
                    sink.addChannelingIntSet(state, variable, domain.higher(current))
                    emitted++
                }
                if (current > domain.min) {
                    sink.addChannelingIntSet(state, variable, domain.lower(current))
                    emitted++
                }
                if (emitted >= REPAIR_CAP) return
            }
            if (emitted > 0) return
        }
    }

    private fun key(state: LocalSearchState, row: Int, variable: Int = -1, value: Long = 0): TupleValues =
        TupleValues(LongArray(tuples[row].size) {
            val coordinate = tuples[row][it]
            if (coordinate == variable) value else state.assignment.intValue(coordinate)
        })

    private fun adjust(counts: MutableMap<TupleValues, Int>, key: TupleValues, change: Int): Int {
        val before = counts[key] ?: 0
        val after = before + change
        if (after == 0) counts.remove(key) else counts[key] = after
        return maxOf(0, after - 1) - maxOf(0, before - 1)
    }

    private class TupleState(val keys: Array<TupleValues>, val counts: MutableMap<TupleValues, Int>, var excess: Int = 0)

    private companion object {
        const val REPAIR_CAP = 4
    }
}
