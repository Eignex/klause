package com.eignex.klause.lp.bounding

internal class LpNodeQueryFunding(private val opsPerNode: Long, warmup: Int) {
    private val capacity = if (opsPerNode <= 0L) 0L else {
        val count = warmup.coerceAtLeast(1).toLong()
        if (opsPerNode > Long.MAX_VALUE / count) Long.MAX_VALUE else opsPerNode * count
    }
    private var credit = capacity
    private var observedWork = -1L

    val available: Boolean get() = opsPerNode <= 0L || credit > 0L

    fun observeWork(totalWork: Long) {
        if (opsPerNode <= 0L) return
        val previous = observedWork
        observedWork = totalWork
        if (previous >= 0L) {
            recordWork(if (totalWork == Long.MAX_VALUE) Long.MAX_VALUE else totalWork - previous)
        }
    }

    fun observeNode() {
        if (opsPerNode <= 0L) return
        credit = if (credit < 0L) credit + opsPerNode else {
            credit + minOf(opsPerNode, capacity - credit)
        }
    }

    fun recordWork(work: Long) {
        if (opsPerNode <= 0L || work <= 0L) return
        credit = if (credit < Long.MIN_VALUE + work) Long.MIN_VALUE else credit - work
    }
}
