package com.eignex.klause.propagation

import com.eignex.klause.util.ExecutionControl

internal class PropagationControl(private val parent: ExecutionControl, private val additional: ExecutionControl) :
    ExecutionControl() {
    private val owner = if (parent.accounting != null) parent else additional
    private var nativeControl: ExecutionControl? = null
    override val accounting get() = owner.accounting
    override val stopPolling = parent.stopPolling ?: additional.stopPolling
    override val workScheduled get() = parent.workScheduled || additional.workScheduled
    override fun isCancelled(): Boolean = parent() || additional()
    override fun deadline() = parent.deadline().let { first ->
        val second = additional.deadline()
        if (first == null) second else if (second == null || first <= second) {
            first
        } else {
            second
        }
    }

    override val admission get() = (
        nativeControl ?: (parent.withAccounting(owner) or additional).also {
        nativeControl = it
    }
    ).admission
}
