package com.eignex.klause.util

import kotlin.time.ComparableTimeMark

// A token that fires when cancelled does and reads deadlineOf each time, so a re-armed deadline stays current. It
// carries no work meter. Adapters wrapping a predicate build it from this rather than a bare lambda, which
// ExecutionControl.shorten would read as having no time limit.
internal fun cancelledWhen(
    deadlineOf: () -> ComparableTimeMark?,
    workScheduledOf: () -> Boolean = { false },
    pollingOf: () -> StopPolling? = { null },
    cancelled: () -> Boolean,
): ExecutionControl = PredicateExecutionControl(cancelled, deadlineOf, workScheduledOf, pollingOf)

internal fun ExecutionControl.workScheduled(): Boolean = workScheduled

// Reports every unit charged to this control to record, for a caller that meters its own share.
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
