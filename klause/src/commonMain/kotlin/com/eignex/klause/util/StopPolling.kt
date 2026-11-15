package com.eignex.klause.util

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class StopPolling(private val quantum: Int = 1024) {
    private var remaining = 0

    fun reset() {
        remaining = 0
    }

    fun rearm() {
        remaining = quantum
    }

    fun due(work: Int): Boolean {
        remaining -= work
        if (remaining > 0) return false
        remaining = quantum
        return true
    }
}

@Suppress("NOTHING_TO_INLINE") // Hot loops share one worker countdown across nested operations.
internal inline fun ExecutionControl.pollStop(work: Int = 1): Boolean {
    if (this === ExecutionControl.Never) return false
    val polling = stopPolling
    return (polling == null || polling.due(work)) && isCancelled()
}

internal fun ExecutionControl.bindStopControl(): ExecutionControl =
    if (this === ExecutionControl.Never) this else WorkerStopControl(unboundStopControl())

internal fun ExecutionControl.unboundStopControl(): ExecutionControl = if (this is WorkerStopControl) original else this

private class WorkerStopControl(val original: ExecutionControl) : ExecutionControl(original) {
    override val stopPolling = StopPolling()
    override fun isCancelled(): Boolean = original()
}

internal fun followStopControl(current: () -> ExecutionControl): ExecutionControl = object : ExecutionControl() {
    override fun isCancelled(): Boolean = current()()
    override fun deadline() = current().deadline()
    override val workScheduled get() = current().workScheduled
    override val stopPolling get() = current().stopPolling
}

@OptIn(ExperimentalAtomicApi::class)
internal fun ExecutionControl.withStopFlag(flag: AtomicBoolean): ExecutionControl {
    val parent = this
    return object : ExecutionControl() {
        override fun isCancelled(): Boolean = flag.load() || parent()
        override fun deadline() = parent.deadline()
        override val workScheduled get() = parent.workScheduled
        override val stopPolling get() = parent.stopPolling
    }
}
