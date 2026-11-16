package com.eignex.klause.theory.qflra

import com.eignex.klause.util.ArithmeticWorkSource
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.StopPolling
import com.eignex.klause.util.WorkMeter
import kotlin.jvm.JvmField

internal class LinearOperationCancellation(
    private val source: () -> ExecutionControl,
    private val containingStopped: () -> Boolean,
) : ExecutionControl() {
    @JvmField var active = false
    @JvmField val polling = StopPolling(8192)
    @JvmField var stopped = false
    private var boundAccounting: WorkMeter? = null
    private var boundAdmission: ArithmeticWorkSource? = null

    fun begin() {
        val control = source()
        boundAccounting = control.accounting
        boundAdmission = control.admission
        active = true
        polling.reset()
        stopped = false
    }

    fun end() {
        active = false
        polling.reset()
        boundAccounting = null
        boundAdmission = null
    }

    fun poll(): Boolean {
        if (--polling.remaining > 0) return false
        return isCancelled()
    }

    override fun isCancelled(): Boolean {
        if (!active) {
            polling.reset()
            return source()() || containingStopped()
        }
        polling.rearm()
        stopped = stopped || source()() || containingStopped()
        if (stopped) polling.reset()
        return stopped
    }

    override fun deadline() = source().deadline()
    override val stopPolling get() = if (active) polling else source().stopPolling
    override val accounting get() = if (active) boundAccounting else source().accounting
    override val admission get() = if (active) boundAdmission else admissionFrom(source())
}
