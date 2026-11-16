package com.eignex.klause.util

import com.eignex.kumulant.core.Concurrency
import com.eignex.util.concurrent.AtomicCells

internal class WorkCreditPool(val limit: Long?, val concurrency: Concurrency = Concurrency.None) {
    private val counter = WorkCounter(concurrency)
    private var serialAvailable = limit ?: 0L
    private val sharedAvailable = if (concurrency == Concurrency.None || limit == null) {
        null
    } else {
        AtomicCells.newLong(
            limit,
        )
    }
    val spent: Long get() = counter.value
    val exhausted: Boolean get() = limit?.let { spent >= it } == true

    init {
        require(limit == null || limit >= 0L)
    }

    fun reserve(maximum: Int): WorkReservation? {
        require(maximum > 0)
        if (limit == null) return WorkReservation(maximum) { record(it.toLong()) }
        val available = sharedAvailable
        if (available == null) {
            val granted = minOf(maximum.toLong(), serialAvailable.coerceAtLeast(0L)).toInt()
            if (granted == 0) return null
            serialAvailable -= granted
            return WorkReservation(granted) { settle(granted, it) }
        }
        while (true) {
            val current = available.load()
            val granted = minOf(maximum.toLong(), current.coerceAtLeast(0L)).toInt()
            if (granted == 0) return null
            if (available.compareAndSet(current, current - granted)) {
                return WorkReservation(granted) { used -> settle(granted, used) }
            }
        }
    }

    fun debit(units: Long) {
        require(units >= 0L)
        if (units == 0L) return
        if (limit != null) adjustAvailable(-units)
        record(units)
    }

    private fun record(units: Long) {
        counter.add(units)
    }

    private fun settle(granted: Int, used: Int) {
        adjustAvailable((granted - used).toLong())
        record(used.toLong())
    }

    private fun adjustAvailable(units: Long) {
        val available = sharedAvailable
        if (available == null) {
            serialAvailable = adjust(serialAvailable, units)
            return
        }
        while (true) {
            val current = available.load()
            val next = adjust(current, units)
            if (current == next || available.compareAndSet(current, next)) return
        }
    }

    private fun adjust(current: Long, units: Long): Long = when {
        units < 0 && current < Long.MIN_VALUE - units -> Long.MIN_VALUE
        units > 0 && current > Long.MAX_VALUE - units -> Long.MAX_VALUE
        else -> current + units
    }
}

@Suppress("TooGenericExceptionCaught") // failed parent admission must return every child reservation
internal fun reserveWork(
    maximum: Int,
    pools: List<WorkCreditPool>,
    parent: ArithmeticWorkSource?,
    committed: (Int) -> Unit,
): WorkReservation? {
    val containingOwner = parent?.takeIf { it.metered }
    val reservations = ArrayList<WorkReservation>()
    var capacity = maximum
    for (pool in pools) {
        val grant = pool.reserve(capacity)
        if (grant == null) {
            reservations.forEach { it.settle(0) }
            return null
        }
        reservations.add(grant)
        capacity = minOf(capacity, grant.capacity)
    }
    val containing = try {
        containingOwner?.reserve(capacity)
    } catch (failure: Throwable) {
        reservations.forEach { it.settle(0) }
        throw failure
    }
    if (containingOwner != null && containing == null) {
        reservations.forEach { it.settle(0) }
        return null
    }
    return WorkReservation(capacity.coerceAtMost(containing?.capacity ?: capacity)) { used ->
        try {
            reservations.forEach { it.settle(used) }
            committed(used)
        } finally {
            containing?.settle(used)
        }
    }
}
