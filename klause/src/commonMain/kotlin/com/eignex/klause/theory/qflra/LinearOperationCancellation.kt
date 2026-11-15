package com.eignex.klause.theory.qflra

import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.StopPolling
import com.eignex.klause.util.WorkMeter

internal class LinearOperationCancellation(
    private val source: () -> ExecutionControl,
    private val containingStopped: () -> Boolean,
) : ExecutionControl() {
    private var active = false
    private val polling = StopPolling(2048)
    private var stopped = false
    private var boundAccounting: WorkMeter? = null

    fun begin() {
        val control = source()
        boundAccounting = control.accounting
        active = true
        polling.reset()
        stopped = false
    }

    fun end() {
        active = false
        boundAccounting = null
    }

    @Suppress("NOTHING_TO_INLINE") // Keep the local countdown in scalar scan loops.
    inline fun poll(): Boolean {
        if (!active) return isCancelled()
        if (stopped) return true
        if (!polling.due(1)) return false
        return isCancelled()
    }

    override fun isCancelled(): Boolean {
        if (!active) return source()() || containingStopped()
        polling.rearm()
        stopped = stopped || source()() || containingStopped()
        return stopped
    }

    override fun deadline() = source().deadline()
    override val stopPolling get() = if (active) polling else source().stopPolling
    override val accounting get() = if (active) boundAccounting else source().accounting
    override val admission get() = admissionFrom(source())
}
