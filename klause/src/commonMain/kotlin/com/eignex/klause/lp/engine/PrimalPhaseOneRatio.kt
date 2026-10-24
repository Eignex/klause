package com.eignex.klause.lp.engine

import com.eignex.klause.util.argsortBy
import kotlin.math.abs

internal data class PrimalPhaseOneStep(
    val length: Double,
    val leaving: Int,
    val toUpper: Boolean,
    val passed: Int,
)

internal class PrimalPhaseOneRatio(rows: Int) {
    private val times = DoubleArray(2 * rows)
    private val slots = IntArray(2 * rows)
    private val upper = BooleanArray(2 * rows)
    private val order = IntArray(2 * rows)
    private val scratch = IntArray(2 * rows)
    private var count = 0

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    fun choose(
        model: LpModel,
        numerical: LpScalingView,
        headings: IntArray,
        beta: DoubleArray,
        alpha: DoubleArray,
        direction: Double,
        range: Double,
        tolerance: Double,
        work: LpWork,
    ): PrimalPhaseOneStep? {
        count = 0
        var slope = 0.0
        work.add(8L * headings.size)
        for (i in headings.indices) {
            val rate = -alpha[i] * direction
            if (abs(rate) < tolerance) continue
            val column = headings[i]
            if (model.hasFiniteLower(column)) {
                val lower = numerical.lowerD(column)
                if (beta[i] < lower) slope -= rate
                if ((rate > 0.0 && beta[i] < lower) || (rate < 0.0 && beta[i] >= lower)) {
                    add((lower - beta[i]) / rate, i, false, range)
                }
            }
            if (model.hasFiniteUpper(column)) {
                val higher = numerical.upperD(column)
                if (beta[i] > higher) slope += rate
                if ((rate > 0.0 && beta[i] <= higher) || (rate < 0.0 && beta[i] > higher)) {
                    add((higher - beta[i]) / rate, i, true, range)
                }
            }
        }
        if (!slope.isFinite() || slope >= -tolerance) return null
        var levels = 0
        var remaining = count - 1
        while (remaining > 0) {
            levels++
            remaining = remaining ushr 1
        }
        work.add(count.toLong() * (4L + 2L * levels))
        val sorted = argsortBy(count, order, scratch) { a, b -> times[a].compareTo(times[b]) }
        var position = 0
        while (position < count) {
            val first = sorted[position]
            val length = times[first]
            // The entering column can end the edge without changing the basis.
            if (range <= length) return PrimalPhaseOneStep(range, -1, false, position)
            var selected = first
            val passed = position
            do {
                val event = sorted[position++]
                slope += abs(alpha[slots[event]])
                if (abs(alpha[slots[event]]) > abs(alpha[slots[selected]])) selected = event
            } while (position < count && times[sorted[position]] <= length + tolerance)
            // Bound crossings increase the slope of the convex sum of basic-bound violations.
            if (slope >= -tolerance) {
                return PrimalPhaseOneStep(times[selected], slots[selected], upper[selected], passed)
            }
        }
        return if (range < Double.MAX_VALUE) PrimalPhaseOneStep(range, -1, false, count) else null
    }

    private fun add(time: Double, slot: Int, toUpper: Boolean, range: Double) {
        if (!time.isFinite() || time < 0.0 || time > range) return
        times[count] = time
        slots[count] = slot
        upper[count++] = toUpper
    }
}
