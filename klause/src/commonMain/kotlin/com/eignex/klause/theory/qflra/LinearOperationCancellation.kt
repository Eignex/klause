package com.eignex.klause.theory.qflra

import com.eignex.klause.util.Cancellation

internal class LinearOperationCancellation(
    private val source: () -> Cancellation,
    private val containingStopped: () -> Boolean,
) : Cancellation {
    private var active = false
    private var remaining = 0
    private var stopped = false

    fun begin() {
        active = true
        remaining = 0
        stopped = false
    }

    fun end() {
        active = false
    }

    override fun isCancelled(): Boolean {
        if (!active) return source()() || containingStopped()
        if (stopped) return true
        // Metered checks retain their exact debit boundary; unmetered numerical loops poll in fixed batches.
        if (source().workMeter() == null && remaining > 0) {
            remaining--
            return false
        }
        remaining = 63
        stopped = source()() || containingStopped()
        return stopped
    }

    override fun deadline() = source().deadline()
    override fun workMeter() = source().workMeter()
}
