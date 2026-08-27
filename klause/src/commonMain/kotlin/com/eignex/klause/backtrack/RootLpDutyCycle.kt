package com.eignex.klause.backtrack

/** Deterministic 1:1 duty cycle for repeated root LP work. */
internal class RootLpDutyCycle {
    private var lastRootCost = 0L
    private var lastRootEnd = 0L
    private var hasRun = false

    fun allows(totalWork: Long): Boolean = !hasRun || saturatedSubtract(totalWork, lastRootEnd) >= lastRootCost

    fun record(before: Long, after: Long) {
        lastRootCost = if (before == Long.MAX_VALUE && after == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            saturatedSubtract(after, before)
        }
        lastRootEnd = after
        hasRun = true
    }

    private fun saturatedSubtract(after: Long, before: Long): Long = if (after <= before) 0L else after - before
}
