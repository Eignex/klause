package com.eignex.klause.presolve

import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.withoutQuotaViews
import kotlin.jvm.JvmField

internal class PresolveScan(private val parent: ExecutionControl) :
    ExecutionControl(parent),
    AutoCloseable {
    override val accounting = parent.accounting
    private val source = parent.admission

    @JvmField val physical = source?.withoutQuotaViews()

    @JvmField val budget = source?.quotaOwner as? PresolveBudget

    @JvmField val end = (budget?.spent() ?: 0L).let { spent ->
        spent + minOf(source?.quotaRemaining ?: Long.MAX_VALUE, Long.MAX_VALUE - spent)
    }

    @JvmField var countdown = 0

    @JvmField val polling = parent.stopPolling

    @JvmField var stopped = false

    override val admission get() = source
    override val workScheduled get() = parent.workScheduled
    override fun deadline() = parent.deadline()

    @Suppress("NOTHING_TO_INLINE") // Scalar scans keep their countdown and quota reads in the loop.
    inline fun poll(): Boolean {
        if (stopped) return true
        if (quotaExpired() || (polling?.due(1) ?: (countdown-- <= 0))) return isCancelled()
        return false
    }

    override fun isCancelled(): Boolean {
        if (stopped) return true
        countdown = 255
        stopped = parent() || quotaExpired()
        if (stopped) parent.presolveBudget()?.activeInvocation?.interrupted = true
        return stopped
    }

    @Suppress("NOTHING_TO_INLINE") // Avoid a forwarding call for every visited source entry.
    private inline fun quotaExpired(): Boolean {
        val counter = budget
        return (counter != null && counter.spent() >= end) || physical?.exhausted == true
    }

    override fun close() {
        if (polling == null) isCancelled() else poll()
    }
}
