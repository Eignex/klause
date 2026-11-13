package com.eignex.klause.util

import com.eignex.kumulant.core.Concurrency
import com.eignex.util.concurrent.AtomicCells

internal class WorkCreditPool(val limit: Long?, val concurrency: Concurrency = Concurrency.None) {
    private data class State(val spent: Long = 0L, val reserved: Long = 0L)
    private var serialSpent = 0L
    private var serialReserved = 0L
    private val shared = if (concurrency == Concurrency.None) null else AtomicCells.newReference(State())
    val spent: Long get() = shared?.load()?.spent ?: serialSpent
    val exhausted: Boolean get() = limit?.let { spent >= it } == true

    init { require(limit == null || limit >= 0L) }

    fun reserve(maximum: Int): WorkReservation? {
        require(maximum > 0)
        val cap = limit ?: return WorkReservation(maximum) { debit(it.toLong()) }
        val state = shared
        if (state == null) {
            val granted = minOf(maximum.toLong(), available(cap, serialSpent, serialReserved)).toInt()
            if (granted == 0) return null
            serialReserved += granted
            return WorkReservation(granted) { settle(granted, it) }
        }
        while (true) {
            val current = state.load()
            val remaining = available(cap, current.spent, current.reserved)
            val granted = minOf(maximum.toLong(), remaining).toInt()
            if (granted == 0) return null
            if (state.compareAndSet(current, current.copy(reserved = current.reserved + granted))) {
                return WorkReservation(granted) { used -> settle(granted, used) }
            }
        }
    }

    fun debit(units: Long) {
        require(units >= 0L)
        if (units == 0L) return
        val state = shared
        if (state == null) {
            serialSpent = sum(serialSpent, units)
            return
        }
        while (true) {
            val current = state.load()
            val next = current.copy(spent = sum(current.spent, units))
            if (state.compareAndSet(current, next)) return
        }
    }

    private fun settle(granted: Int, used: Int) {
        val state = shared
        if (state == null) {
            check(serialReserved >= granted)
            serialSpent = sum(serialSpent, used.toLong())
            serialReserved -= granted
            return
        }
        while (true) {
            val current = state.load()
            check(current.reserved >= granted)
            val next = State(sum(current.spent, used.toLong()), current.reserved - granted)
            if (state.compareAndSet(current, next)) return
        }
    }

    private fun sum(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)
    private fun available(cap: Long, spent: Long, reserved: Long): Long =
        ((cap - spent).coerceAtLeast(0L) - reserved).coerceAtLeast(0L)
}

internal fun reserveWork(
    maximum: Int,
    pools: List<WorkCreditPool>,
    parent: ArithmeticWorkSource?,
    committed: (Int) -> Unit,
): WorkReservation? {
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
        parent?.reserve(capacity)
    } catch (failure: Throwable) {
        reservations.forEach { it.settle(0) }
        throw failure
    }
    if (parent != null && containing == null) {
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
