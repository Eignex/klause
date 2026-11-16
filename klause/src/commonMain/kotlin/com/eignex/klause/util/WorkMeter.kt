package com.eignex.klause.util

/**
 * A sink for completed deterministic work, independent of cancellation and time.
 *
 * The owner defines a unit, such as a coefficient visit or elimination update. Exact allowances
 * admit work separately; recording completed units neither grants work nor evaluates a stop predicate.
 */
fun interface WorkMeter {
    /** Record [units] of work done. */
    fun charge(units: Long)
}
