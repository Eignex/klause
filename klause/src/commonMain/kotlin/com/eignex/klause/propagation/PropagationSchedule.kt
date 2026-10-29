package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayDeque

internal class PropagationSchedule {
    private val costs = LongArray(FactorFamily.entries.size)
    private var bypasses = 0
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
            bypasses = 0
            return queue.removeFirst()
        }
        var selected = 0
        var cheapest = costs[family(queue[0]).ordinal]
        for (i in 1 until minOf(queue.size, LOOKAHEAD)) {
            selectionWork++
            val cost = costs[family(queue[i]).ordinal]
            if (cost < cheapest) {
                selected = i
                cheapest = cost
            }
        }
        // Stable removal keeps the oldest factor at the head until its bounded bypass allowance expires.
        bypasses = if (selected == 0) 0 else bypasses + 1
        selectionWork += selected
        return queue.removeAt(selected)
    }

    internal val capacityBytes: Long get() = costs.size.toLong() * Long.SIZE_BYTES

    private companion object {
        const val LOOKAHEAD = 8
        const val MAX_BYPASSES = 8
    }
}
