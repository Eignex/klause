package com.eignex.klause.util

/**
 * A budget counted in deterministic work units rather than elapsed time.
 *
 * An engine charges the work it does at the points where it already polls its [Cancellation], so a stop
 * keyed on the meter falls at the same point of the same run however loaded the machine is. A unit is
 * the engine's own proxy for cost — entries touched, not seconds — so it is reproducible where a clock
 * is not.
 */
fun interface WorkMeter {
    /** Record [units] of work done. */
    fun charge(units: Long)
}
