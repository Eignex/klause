package com.eignex.klause.solver.resources

import com.eignex.kumulant.core.Concurrency
import com.eignex.util.concurrent.AtomicCells

// Allowances need exact integer saturation; floating-point statistical sums lose unit debits above 2^53.
internal class WorkCounter(concurrency: Concurrency, initial: Long = 0L) {
    private var serial = initial
    private val shared = if (concurrency == Concurrency.None) null else AtomicCells.newLong(initial)

    val value: Long get() = shared?.load() ?: serial

    fun add(units: Long) {
        require(units >= 0L)
        val cell = shared
        if (cell == null) {
            serial = sum(serial.coerceAtLeast(0L), units)
            return
        }
        while (true) {
            val current = cell.load()
            val next = sum(current.coerceAtLeast(0L), units)
            if (current == next || cell.compareAndSet(current, next)) return
        }
    }

    private fun sum(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)
}
