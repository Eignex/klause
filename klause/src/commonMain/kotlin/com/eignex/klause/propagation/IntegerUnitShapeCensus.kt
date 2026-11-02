package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.klause.util.addExact
import com.eignex.klause.util.subExact

internal class IntegerUnitShapeCensus {
    private enum class Count {
        Rows, LimitUnavailable, LimitOutsideHistogram, Impossible, Tautology, AllFalse, AtMostOne,
        SingleClause, NotSingleClause, RootRows, RootUnavailable, RootLimitOutsideHistogram, RootFixedTerms,
        RootImpossible, RootTautology, RootAllFalse, RootAtMostOne, RootSingleClause, RootNotSingleClause,
    }
    private val counts = LongArray(Count.entries.size)
    private val limits = LongArray(INTEGER_LEARNING_TERMS + 1)
    private val rootWidths = LongArray(INTEGER_LEARNING_TERMS + 1)
    private val rootLimits = LongArray(INTEGER_LEARNING_TERMS + 1)
    private var saturated = false

    private fun increment(count: Count, value: Long = 1L) { increment(counts, count.ordinal, value) }

    private fun increment(values: LongArray, index: Int, value: Long = 1L) {
        if (value > Long.MAX_VALUE - values[index]) {
            values[index] = Long.MAX_VALUE
            saturated = true
        } else values[index] += value
    }

    fun admit(row: IntegerConflictRow, rootDomain: ((Int) -> IntDomain?)?) {
        if (row.coefficients.any { it != 1L && it != -1L }) return
        increment(Count.Rows)
        try {
            var limit = row.bound
            for (coefficient in row.coefficients) if (coefficient < 0L) limit = addExact(limit, 1L)
            classify(row.variables.size, limit, root = false)
        } catch (_: CheckedLongOverflowException) {
            increment(Count.LimitUnavailable)
        }
        if (rootDomain == null) {
            increment(Count.RootUnavailable)
            return
        }
        try {
            classifyRoot(row, rootDomain)
        } catch (_: CheckedLongOverflowException) {
            increment(Count.RootUnavailable)
        }
    }

    private fun classifyRoot(row: IntegerConflictRow, rootDomain: (Int) -> IntDomain?) {
        var limit = row.bound
        var width = 0
        for (index in row.variables.indices) {
            val domain = rootDomain(row.variables[index])
            if (domain == null || domain.min !in 0L..1L || domain.max !in 0L..1L || domain.min > domain.max) {
                increment(Count.RootUnavailable)
                return
            }
            val coefficient = row.coefficients[index]
            if (domain.min == domain.max) limit = subExact(limit, coefficient * domain.min)
            else {
                width++
                if (coefficient < 0L) limit = addExact(limit, 1L)
            }
        }
        increment(Count.RootRows)
        increment(Count.RootFixedTerms, (row.variables.size - width).toLong())
        increment(rootWidths, width)
        classify(width, limit, root = true)
    }

    private fun classify(width: Int, limit: Long, root: Boolean) {
        if (limit in 0L..INTEGER_LEARNING_TERMS.toLong()) {
            increment(if (root) rootLimits else limits, limit.toInt())
        } else increment(if (root) Count.RootLimitOutsideHistogram else Count.LimitOutsideHistogram)
        when {
            limit < 0L -> increment(if (root) Count.RootImpossible else Count.Impossible)
            limit >= width -> increment(if (root) Count.RootTautology else Count.Tautology)
            else -> {
                if (limit == 0L) increment(if (root) Count.RootAllFalse else Count.AllFalse)
                if (limit == 1L && width >= 2) increment(if (root) Count.RootAtMostOne else Count.AtMostOne)
                if (limit == width - 1L) increment(if (root) Count.RootSingleClause else Count.SingleClause)
                else increment(if (root) Count.RootNotSingleClause else Count.NotSingleClause)
            }
        }
    }

    fun snapshot(): Map<String, Long> = buildMap {
        for (count in Count.entries) put("learningIntegerCensusUnit" + count.name, counts[count.ordinal])
        for (limit in limits.indices) put("learningIntegerCensusUnitLimit" + limit, limits[limit])
        for (width in rootWidths.indices) put("learningIntegerCensusUnitRootWidth" + width, rootWidths[width])
        for (limit in rootLimits.indices) put("learningIntegerCensusUnitRootLimit" + limit, rootLimits[limit])
        put("learningIntegerCensusUnitSaturated", if (saturated) 1L else 0L)
    }

    fun sharingMemory(sample: SharingMemoryObservation) {
        for (values in arrayOf(counts, limits, rootWidths, rootLimits)) {
            sample.owned(values, values.size.toLong() * Long.SIZE_BYTES, false)
        }
    }
}
