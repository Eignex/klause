package com.eignex.klause.util

import kotlin.time.TimeSource

/** Calibration-only tally of charged units per kind and wall time per section. */
object WorkTally {
    private val units = LinkedHashMap<String, Long>()
    private val nanos = LinkedHashMap<String, Long>()

    /** Add [n] units of [name]. */
    fun add(name: String, n: Long) {
        units[name] = (units[name] ?: 0L) + n
    }

    /** Run [block], adding its wall time to [name]. */
    inline fun <T> timed(name: String, block: () -> T): T {
        val start = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            addNanos(name, start.elapsedNow().inWholeNanoseconds)
        }
    }

    /** Add [n] nanoseconds to [name]. */
    fun addNanos(name: String, n: Long) {
        nanos[name] = (nanos[name] ?: 0L) + n
    }

    /** One line of every tally. */
    fun report(): String = "TALLY units " + units.entries.joinToString(",") { "${it.key}=${it.value}" } +
        " ms " + nanos.entries.joinToString(",") { "${it.key}=${it.value / 1_000_000}" }
}

/** Charge [units] of [name] work, tallying it when the token carries a meter. */
fun Cancellation.chargeTally(name: String, units: Long) {
    if (workMeter() == null) return
    WorkTally.add(name, units)
    charge(units)
}
