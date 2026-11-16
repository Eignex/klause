package com.eignex.klause.util

import com.eignex.kumulant.core.Concurrency
import com.eignex.util.concurrent.AtomicCells
import com.eignex.util.numbers.saturatedAdd

// Allowances need exact integer saturation; floating-point statistical sums lose unit debits above 2^53.
internal class WorkCounter(val concurrency: Concurrency = Concurrency.None, initial: Long = 0L) {
    private var serial = initial
    private val shared = if (concurrency == Concurrency.None) null else AtomicCells.newLong(initial)

    val value: Long get() = shared?.load() ?: serial

    fun add(units: Long) {
        require(units >= 0L)
        val cell = shared
        if (cell == null) {
            serial = saturatedAdd(serial.coerceAtLeast(0L), units)
            return
        }
        while (true) {
            val current = cell.load()
            val next = saturatedAdd(current.coerceAtLeast(0L), units)
            if (current == next || cell.compareAndSet(current, next)) return
        }
    }
}
