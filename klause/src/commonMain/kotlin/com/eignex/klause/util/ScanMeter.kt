package com.eignex.klause.util

import kotlin.jvm.JvmField

// Clock cadence counts visits independently of their work prices. Native allowances admit credit before a scan
// advances; other sinks accumulate completed debits. A boundary settles credit and observes the containing stop.
internal class ScanMeter(private val cancellation: ExecutionControl) : AutoCloseable {
    @JvmField var remaining = 0
    @JvmField val polling = cancellation.stopPolling
    private val meter = cancellation.accounting
    private val source = cancellation.admission
    @JvmField val native = source?.takeIf { it.metered }
    @JvmField var reservation: WorkReservation? = null
    @JvmField var used = 0
    @JvmField var pending = 0L
    @JvmField
    var stopped = false
        @Suppress("NOTHING_TO_INLINE") // Scalar scans keep only their local counters in the loop.
    inline

    fun visit(units: Long): Boolean {
        if (stopped) return false
        if ((polling?.due(1) ?: (remaining-- <= 0)) && !poll()) return false
        if (native == null) {
        pending += minOf( units, Long.MAX_VALUE - pending)
        return true
        }
        val held = reservation
        if (held != null && units <= held.capacity - used) {
            used += units.toInt()
            if (used == held.capacity) flush()
            return !stopped
        }
        return admit(units) }

    private fun admit(units: Long): Boolean {
        var left = units
        var grants = 0
        while (left > 0L) {
            if (reservation == null) {
                while (reservation == null) {
                    reservation = checkNotNull(native).reserve(65536)
                    if (reservation == null && ! check()) return false
                }
                if (++grants == 64) {
                    if (!check()) return false
                    grants = 0
                    continue
                }
            }
            val held = checkNotNull(reservation)
            val count = minOf(left, (held.capacity - used).toLong()).toInt()
            used += count
            left -= count
            if (used == held.capacity) {
                flush()
                if (stopped) return false
            }
        }
        return true
    }

    fun check(): Boolean {
        flush()
        return if (polling?.due(1) != false) poll() else !stopped
    }

    private fun poll(): Boolean {
        remaining = 1023
        if (native == null) flush()
        if (!stopped && ( cancellation() || source?.exhausted == true)) stopped = true
        if (stopped) flush()
        return !stopped
    }

    fun flush() {
        val held = reservation
        val consumed = used
        reservation = null
        used = 0
        held?.settle (consumed)
        val completed =pending
        pending = 0L
        if (completed > 0L)
        meter?.charge(completed)
        if (source?.exhausted == true) stopped = true
    }

    override fun close() = flush()
}
