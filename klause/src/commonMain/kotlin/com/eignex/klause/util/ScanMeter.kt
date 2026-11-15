package com.eignex.klause.util

// Clock cadence counts visits independently of their work prices. Native allowances admit credit before a scan
// advances; other sinks accumulate completed debits. A boundary settles credit and observes the containing stop.
internal class ScanMeter(private val cancellation: ExecutionControl) : AutoCloseable {
    private var remaining = 0
    private val meter = cancellation.accounting
    private val source = cancellation.admission
    private val native = source?.takeIf { it.metered }
    private var reservation: WorkReservation? = null
    private var used = 0
    private var pending = 0L
    var stopped = false
        private set

    @Suppress("NOTHING_TO_INLINE") // Scalar scans keep only their local counters in the loop.
    inline fun visit(units: Long): Boolean {
        if (stopped) return false
        if (remaining-- <= 0 && !poll()) return false
        if (native == null) {
            pending += minOf(units, Long.MAX_VALUE - pending)
            return true
        }
        val held = reservation
        if (held != null && units <= held.capacity - used) {
            used += units.toInt()
            if (used == held.capacity) flush()
            return !stopped
        }
        return admit(units)
    }

    private fun admit(units: Long): Boolean {
        var left = units
        var grants = 0
        while (left > 0L) {
            if (reservation == null) {
                while (reservation == null) {
                    reservation = checkNotNull(native).reserve(65536)
                    if (reservation == null && !check()) return false
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
        return poll()
    }

    private fun poll(): Boolean {
        remaining = 1023
        if (native == null) flush()
        if (!stopped && (cancellation() || source?.exhausted == true)) stopped = true
        if (stopped) flush()
        return !stopped
    }

    fun flush() {
        val held = reservation
        val consumed = used
        reservation = null
        used = 0
        held?.settle(consumed)
        val completed = pending
        pending = 0L
        if (completed > 0L) meter?.charge(completed)
        if (source?.exhausted == true) stopped = true
    }

    override fun close() = flush()
}
