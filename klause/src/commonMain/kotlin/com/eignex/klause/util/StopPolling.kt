package com.eignex.klause.util

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmField

internal class StopPolling(@JvmField val quantum: Int = 1024) {
    @JvmField var remaining = 0

    fun reset() {
        remaining = 0
    }

    fun rearm() {
        remaining = quantum
    }

    @Suppress("NOTHING_TO_INLINE") // JVM callers use the owned fields without accessor or C1 inline depth costs.
    inline fun due(work: Int): Boolean {
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

/** Bind one executing worker's bounded checkpoints; direct stop observations remain immediate. */
fun ExecutionControl.withBoundedPolling(): ExecutionControl = bindStopControl()

internal fun ExecutionControl.unboundStopControl(): ExecutionControl = if (this is WorkerStopControl) original else this

private class WorkerStopControl(val original: ExecutionControl) : ExecutionControl(original) {
    override val containingStop get() = original
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
