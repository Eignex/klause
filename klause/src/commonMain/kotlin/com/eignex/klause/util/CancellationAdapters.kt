package com.eignex.klause.util

import kotlin.time.ComparableTimeMark

/**
 * A token that fires when [cancelled] does and states [deadlineOf] as its deadline, read each time it is asked so a
 * deadline re-armed later stays current. It carries no work meter. An adapter that wraps a token in a predicate builds
 * it from this rather than a bare lambda, which [ExecutionControl.shorten] would read as having no time limit at all.
 */
internal fun cancelledWhen(
    deadlineOf: () -> ComparableTimeMark?,
    workScheduledOf: () -> Boolean = { false },
    pollingOf: () -> StopPolling? = { null },
    cancelled: () -> Boolean,
): ExecutionControl = PredicateExecutionControl(cancelled, deadlineOf, workScheduledOf, pollingOf)

internal fun ExecutionControl.workScheduled(): Boolean = workScheduled

/** This control, with every unit it is charged also reported to [record]. */
internal fun ExecutionControl.counting(record: (Long) -> Unit): ExecutionControl {
    val containing = this
    return object : ExecutionControl() {
        override fun isCancelled(): Boolean = containing()
        override fun deadline() = containing.deadline()
        override val accounting: WorkMeter = WorkMeter { units ->
            record(units)
            containing.charge(units)
        }
    }
}
