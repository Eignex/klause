package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayDeque

internal class PropagationSchedule {
    private val costs = LongArray(FactorFamily.entries.size)
    private var bypasses = 0
    private var dispatches = 0L
    private var bypassed = 0L
    private var forced = 0L
    private var totalSelectionWork = 0L
    var selectionWork = 0L
        private set

    fun begin() {
        bypasses = 0
    }

    fun observe(family: FactorFamily, work: Long) {
        val cost = work.coerceAtLeast(1)
        val previous = costs[family.ordinal]
        costs[family.ordinal] = if (previous == 0L) cost else previous + (cost - previous) / 8
    }

    fun remove(queue: IntArrayDeque, family: (Int) -> FactorFamily): Int {
        selectionWork = 1L
        if (bypasses == MAX_BYPASSES || queue.size == 1) {
            if (bypasses == MAX_BYPASSES) forced++
            return take(queue, 0)
        }
        var selected = 0
        var cheapest = costs[family(queue[0]).ordinal]
        // Cheap heads offer little work to amortize speculative prefix inspection.
        if (cheapest <= MAX_SELECTION_WORK) return take(queue, 0)
        for (i in 1 until minOf(queue.size, LOOKAHEAD)) {
            selectionWork++
            val cost = costs[family(queue[i]).ordinal]
            if (cost < cheapest) {
                selected = i
                cheapest = cost
            }
        }
        return take(queue, selected)
    }

    private fun take(queue: IntArrayDeque, selected: Int): Int {
        // Stable removal keeps the oldest factor at the head until its bounded bypass allowance expires.
        bypasses = if (selected == 0) 0 else bypasses + 1
        selectionWork += selected
        dispatches++
        if (selected > 0) bypassed++
        totalSelectionWork += selectionWork.coerceAtMost(Long.MAX_VALUE - totalSelectionWork)
        return queue.removeAt(selected)
    }

    fun snapshot(): Map<String, Long> = mapOf(
        "propagationScheduleDispatches" to dispatches,
        "propagationScheduleBypasses" to bypassed,
        "propagationScheduleForced" to forced,
        "propagationScheduleWork" to totalSelectionWork,
    )

    internal val capacityBytes: Long get() = costs.size.toLong() * Long.SIZE_BYTES

    private companion object {
        const val LOOKAHEAD = 8
        const val MAX_SELECTION_WORK = 2 * LOOKAHEAD - 1
        const val MAX_BYPASSES = 8
    }
}
