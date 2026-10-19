package com.eignex.klause.bench.profile

import java.util.concurrent.TimeUnit

internal class ProfileRunBudget(private val nanoTime: () -> Long = System::nanoTime) {
    private val startedAt = nanoTime()

    fun remainingMillis(reserveMillis: Long = 0): Long {
        require(reserveMillis in 0 until DURATION_MILLIS)
        val remaining = TimeUnit.MILLISECONDS.toNanos(DURATION_MILLIS - reserveMillis) -
            (nanoTime() - startedAt)
        check(remaining > 0) { "Profile run budget exhausted" }
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
    }

    private companion object {
        const val DURATION_MILLIS = 180_000L
    }
}
