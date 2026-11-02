package com.eignex.klause.propagation

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.klause.util.addExact
import com.eignex.klause.util.subExact

internal class IntegerLearningCensus {
    private class Entry(val undo: Long) {
        var admitting = true
        var used = false
        var later = false
        var postUndo = false
    }
    private enum class Count {
        Admitted, Guarded, Binary, BinaryClause, BinaryNonclausal, BinaryTautology, BinaryImpossible, ShapeUnavailable,
        UseEvents, AdmissionUseEvents, LaterUseEvents, PostUndoUseEvents, UsedRows, LaterUsedRows, PostUndoUsedRows,
        BoundPinEvents, RootBoundPinEvents, NonrootBoundPinEvents, AdmissionBoundPinEvents, PostUndoBoundPinEvents,
        BoolPinEvents, OtherPinEvents, SeedUseEvents, AdmissionSeedUseEvents, PostUndoSeedUseEvents, Untracked, Retired,
    }
    private val counts = LongArray(Count.entries.size)
    private val widths = LongArray(INTEGER_LEARNING_TERMS + 1)
    private val clauseWidths = LongArray(INTEGER_LEARNING_TERMS + 1)
    private val rows = HashMap<Int, Entry>()
    private var undo = 0L
    private var maxCoefficient = 0L
    private var saturated = false
    internal val retainedEntries: Int get() = rows.size

    private fun increment(count: Count) { increment(counts, count.ordinal) }

    private fun increment(values: LongArray, index: Int) {
        if (values[index] == Long.MAX_VALUE) saturated = true else values[index]++
    }

    fun admit(factor: Int, row: IntegerConflictRow, bounds: IntBounds) {
        increment(Count.Admitted)
        increment(widths, row.variables.size)
        if (row.guard != Lit.NONE) increment(Count.Guarded)
        for (coefficient in row.coefficients) {
            maxCoefficient = maxOf(maxCoefficient, IntegerConflictRow.magnitude(coefficient))
        }
        if (rows.size < INTEGER_LEARNING_ROWS) rows[factor] = Entry(undo) else increment(Count.Untracked)
        if (row.variables.any { !bounds.hasLower(it) || !bounds.hasUpper(it) ||
                bounds.lower(it) != 0L || bounds.upper(it) != 1L }) return
        increment(Count.Binary)
        classifyBinary(row)
    }

    private fun classifyBinary(row: IntegerConflictRow) {
        try {
            var maximum = 0L
            var negative = 0L
            for (coefficient in row.coefficients) {
                val weight = IntegerConflictRow.magnitude(coefficient)
                maximum = addExact(maximum, weight)
                if (coefficient < 0L) negative = addExact(negative, weight)
            }
            val bound = addExact(row.bound, negative)
            if (bound < 0L) {
                increment(Count.BinaryImpossible)
            } else if (bound >= maximum) {
                increment(Count.BinaryTautology)
            } else {
                classifyBinaryClause(row, maximum, bound)
            }
        } catch (_: CheckedLongOverflowException) {
            increment(Count.ShapeUnavailable)
        }
    }

    private fun classifyBinaryClause(row: IntegerConflictRow, maximum: Long, bound: Long) {
        val excess = subExact(maximum, bound)
        var necessarySum = 0L
        var necessaryTerms = 0
        for (coefficient in row.coefficients) {
            val weight = IntegerConflictRow.magnitude(coefficient)
            if (weight >= excess) {
                necessarySum = addExact(necessarySum, weight)
                necessaryTerms++
            }
        }
        // Flipping a necessary term makes the maximal assignment feasible. Their
        // simultaneous violation must suffice even with every other term minimized.
        if (necessarySum > bound) {
            increment(Count.BinaryClause)
            increment(clauseWidths, necessaryTerms)
        } else increment(Count.BinaryNonclausal)
    }

    fun admissionFinished(factor: Int) { rows[factor]?.admitting = false }

    fun used(factor: Int) {
        val row = rows[factor] ?: return
        increment(Count.UseEvents)
        if (!row.used) {
            row.used = true
            increment(Count.UsedRows)
        }
        if (row.admitting) {
            increment(Count.AdmissionUseEvents)
            return
        }
        increment(Count.LaterUseEvents)
        if (!row.later) {
            row.later = true
            increment(Count.LaterUsedRows)
        }
        if (undo > row.undo) {
            increment(Count.PostUndoUseEvents)
            if (!row.postUndo) {
                row.postUndo = true
                increment(Count.PostUndoUsedRows)
            }
        }
    }

    fun pin(factor: Int, kind: String, level: Int) {
        val row = rows[factor] ?: return
        when (kind) {
            "lower", "upper" -> {
                increment(Count.BoundPinEvents)
                increment(if (level == 0) Count.RootBoundPinEvents else Count.NonrootBoundPinEvents)
                if (row.admitting) increment(Count.AdmissionBoundPinEvents)
                else if (undo > row.undo) increment(Count.PostUndoBoundPinEvents)
            }
            "bool" -> increment(Count.BoolPinEvents)
            else -> increment(Count.OtherPinEvents)
        }
    }

    fun seed(factor: Int) {
        val row = rows[factor] ?: return
        increment(Count.SeedUseEvents)
        if (row.admitting) increment(Count.AdmissionSeedUseEvents)
        else if (undo > row.undo) increment(Count.PostUndoSeedUseEvents)
    }

    fun retracted() { if (undo == Long.MAX_VALUE) saturated = true else undo++ }

    fun retire(factor: Int) { if (rows.remove(factor) != null) increment(Count.Retired) }

    fun remap(base: Int, remap: IntArray) {
        val retained = HashMap<Int, Entry>()
        for ((factor, entry) in rows) {
            val next = remap.getOrNull(factor - base) ?: -1
            if (next >= 0) retained[base + next] = entry else increment(Count.Retired)
        }
        rows.clear()
        rows.putAll(retained)
    }

    fun snapshot(): Map<String, Long> = buildMap {
        for (count in Count.entries) put("learningIntegerCensus" + count.name, counts[count.ordinal])
        for (width in widths.indices) put("learningIntegerCensusWidth" + width, widths[width])
        for (width in clauseWidths.indices) put("learningIntegerCensusClauseWidth" + width, clauseWidths[width])
        put("learningIntegerCensusMaxCoefficient", maxCoefficient)
        put("learningIntegerCensusRetained", rows.size.toLong())
        put("learningIntegerCensusSaturated", if (saturated) 1L else 0L)
    }

    fun sharingMemory(sample: SharingMemoryObservation) {
        sample.owned(counts, counts.size.toLong() * Long.SIZE_BYTES, false)
        sample.owned(widths, widths.size.toLong() * Long.SIZE_BYTES, false)
        sample.owned(clauseWidths, clauseWidths.size.toLong() * Long.SIZE_BYTES, false)
        sample.ownedReferences(rows, rows.size.toLong() * 2L, false)
        for (entry in rows.values) sample.owned(entry, Long.SIZE_BYTES + 4L, false)
    }
}
