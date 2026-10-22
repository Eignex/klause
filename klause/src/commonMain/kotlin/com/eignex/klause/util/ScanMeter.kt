package com.eignex.klause.util

// Charges a scan's visits to its token's meter and polls the token in strides. Reading a composite token can read
// the clock, which costs more than a visit, so visits accumulate locally and every due poll flushes them first:
// each performed visit is charged exactly once, through the meter the token held when the scan began, and a stop is
// seen at most one stride of work late. [check] flushes and polls at once, for entry and for the boundaries where a
// scan publishes its result or hands its token to nested work.
internal class ScanMeter(private val cancellation: Cancellation) {
    private val stride = PollStride()
    private val meter = cancellation.workMeter()
    private var pending = 0L
    var stopped = false
        private set

    fun visit(units: Long): Boolean {
        if (stopped) return false
        pending += units
        return !stride.due(units) || check()
    }

    fun check(): Boolean {
        flush()
        if (!stopped && cancellation()) stopped = true
        return !stopped
    }

    fun flush() {
        if (pending == 0L) return
        meter?.charge(pending)
        pending = 0L
    }
}
