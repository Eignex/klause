package com.eignex.klause.util

// Spaces out clock and cancellation reads in a metered hot loop. A check is due on the first call and again once
// `calls` calls or `units` charged work units have passed, whichever comes first, so the latency of a stop stays
// bounded by the work between checks while most calls skip the read.
internal class PollStride(private val calls: Int = 64, private val units: Long = 4096L) {
    private var callsLeft = 0
    private var unitsLeft = 0L

    fun due(work: Long = 1L): Boolean {
        callsLeft--
        unitsLeft -= work
        if (callsLeft > 0 && unitsLeft > 0L) return false
        callsLeft = calls
        unitsLeft = units
        return true
    }
}
