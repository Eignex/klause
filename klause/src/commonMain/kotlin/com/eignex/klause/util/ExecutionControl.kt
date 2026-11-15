package com.eignex.klause.util

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Explicit execution inputs: a stop predicate and an independent accounting sink. */
open class ExecutionControl(
    /** Stop predicate evaluated at execution checkpoints. */
    cancellationPredicate: Cancellation = Cancellation.Never,
    /** Accounting owner; charging it never evaluates [cancellationPredicate]. */
    accounting: WorkMeter? = null,
) {
    private val initialPredicate = cancellationPredicate
    private val initialAccounting = accounting
    private var accountingOwner: ExecutionControl? = null

    /** Stop predicate evaluated at execution checkpoints. */
    val cancellationPredicate: Cancellation get() {
        val owner = accountingOwner ?: return initialPredicate
        return object : Cancellation {
            override fun isCancelled(): Boolean = owner()
            override fun deadline() = owner.deadline()
        }
    }

    /** Explicitly bound work sink, independent of stop observation. */
    open val accounting: WorkMeter? get() = accountingOwner?.accounting ?: initialAccounting
    internal open val admission: ArithmeticWorkSource? get() = accountingOwner?.admission
    internal open val workScheduled: Boolean get() = accountingOwner?.workScheduled ?: false

    internal fun admissionFrom(owner: ExecutionControl): ArithmeticWorkSource? = owner.admission

    internal open val stopPolling: StopPolling? get() = accountingOwner?.stopPolling

    /** Retain an explicitly selected owner's accounting while adapting its stop predicate. */
    constructor(owner: ExecutionControl) : this() {
        accountingOwner = owner
    }

    /** Bind accounting to [owner] without changing this control's stop predicate. */
    fun withAccounting(owner: ExecutionControl): ExecutionControl = AccountingExecutionControl(this, owner)

    /** Observe the live stop predicate. */
    open fun isCancelled(): Boolean = accountingOwner?.invoke() ?: initialPredicate.isCancelled()

    /** Observe the live stop predicate. */
    @Suppress("NOTHING_TO_INLINE") // Keep callable forwarding out of bounded C1 inlining depth.
    inline operator fun invoke(): Boolean = this !== Never && isCancelled()

    /** Current containing deadline, when one is supplied. */
    open fun deadline(): ComparableTimeMark? = accountingOwner?.deadline() ?: initialPredicate.deadline()

    /** Record completed accounting without evaluating the stop predicate. */
    @Suppress("NOTHING_TO_INLINE") // Hot callers bind the sink without a forwarding method.
    inline fun charge(units: Long) {
        accounting?.charge(units)
    }

    /** Add a stop condition while retaining this execution's accounting owner. */
    infix fun or(other: ExecutionControl): ExecutionControl {
        if (other === Never) return this
        if (this === Never && (other is PredicateExecutionControl || other is DeadlineExecutionControl)) return other
        return OrExecutionControl(this, other)
    }

    /** Require both stop conditions, retaining this execution's accounting owner. */
    infix fun and(other: ExecutionControl): ExecutionControl {
        val owner = this
        return object : ExecutionControl() {
            override val accounting get() = owner.accounting
            override val admission get() = admissionFrom(owner)
            override val workScheduled get() = owner.workScheduled || other.workScheduled
            override fun isCancelled(): Boolean = owner() && other()
        }
    }

    /** Bound execution to a fraction of its remaining deadline. */
    fun shorten(fraction: Double): ExecutionControl {
        require(fraction in 0.0..1.0)
        val end = deadline() ?: return this
        val now = TimeSource.Monotonic.markNow()
        val remaining = end - now
        if (remaining <= Duration.ZERO) return this
        return this or until(now + remaining * fraction)
    }

    /** Standard execution controls. */
    companion object {
        /** Execution without cancellation or accounting. */
        val Never = ExecutionControl()

        /** Execution stopped at [deadline]. */
        fun until(deadline: ComparableTimeMark): ExecutionControl = DeadlineExecutionControl(deadline)

        /** Execution stopped after [duration]. */
        fun after(duration: Duration): ExecutionControl = until(TimeSource.Monotonic.markNow() + duration)
    }
}

private class AccountingExecutionControl(val stop: ExecutionControl, owner: ExecutionControl) :
    ExecutionControl(
        owner,
    ) {
    override fun isCancelled(): Boolean = stop()
    override val stopPolling get() = stop.stopPolling
    override val admission: ArithmeticWorkSource? get() {
        val selected = super.admission ?: return null
        val identity = selected.quotaOwner ?: return selected
        val observed = stop.admission
        return if (observed?.quotaOwner === identity) {
            selected.withQuota(observed)
        } else {
            selected
        }
    }
    override fun deadline() = stop.deadline()
    override val workScheduled get() = stop.workScheduled
}

private class OrExecutionControl(owner: ExecutionControl, private val other: ExecutionControl) :
    ExecutionControl(owner) {
    val predicates: Array<ExecutionControl> = combineStops(owner, other)
    private val end = earlier(owner.deadline(), other.deadline())
    override val admission: ArithmeticWorkSource? get() {
        val selected = super.admission ?: return null
        val identity = selected.quotaOwner ?: return selected
        var bound: ArithmeticWorkSource? = null
        var remaining = selected.quotaRemaining
        for (predicate in predicates) {
            val observed = predicate.admission ?: continue
            if (observed.quotaOwner !== identity) continue
            val credit = observed.quotaRemaining ?: continue
            if (remaining == null || credit < remaining) {
                remaining = credit
                bound = observed
            }
        }
        return if (bound == null) selected else selected.withQuota(bound)
    }
    override val workScheduled = owner.workScheduled || other.workScheduled
    override fun deadline() = end
    override val stopPolling get() = super.stopPolling ?: other.stopPolling
    override fun isCancelled(): Boolean {
        for (predicate in predicates) if (predicate()) return true
        return false
    }
}

private fun earlier(first: ComparableTimeMark?, second: ComparableTimeMark?): ComparableTimeMark? = if (first == null) {
    second
} else if (second == null || first <= second) {
    first
} else {
    second
}

private fun stopRoot(control: ExecutionControl): ExecutionControl {
    var root = control
    while (root is AccountingExecutionControl) root = root.stop
    return root
}

private fun combineStops(owner: ExecutionControl, other: ExecutionControl): Array<ExecutionControl> {
    val left = stopRoot(owner)
    val right = stopRoot(other)
    val first = (left as? OrExecutionControl)?.predicates
    val second = (right as? OrExecutionControl)?.predicates
    val firstSize = first?.size ?: if (left === ExecutionControl.Never) 0 else 1
    val secondSize = second?.size ?: if (right === ExecutionControl.Never) 0 else 1
    val last = first?.lastOrNull() ?: left
    val next = second?.firstOrNull() ?: right
    val merge = firstSize > 0 && secondSize > 0 && last is DeadlineExecutionControl && next is DeadlineExecutionControl
    return Array(firstSize + secondSize - if (merge) 1 else 0) { index ->
        if (merge && index == firstSize - 1) {
            if ((last as DeadlineExecutionControl).end <= (next as DeadlineExecutionControl).end) last else next
        } else if (index < firstSize) {
            first?.get(index) ?: left
        } else {
            second?.get(index - firstSize + if (merge) 1 else 0) ?: right
        }
    }
}

/** Construct execution controls from a stop predicate without an accounting owner. */
fun ExecutionControl(cancelled: () -> Boolean): ExecutionControl = PredicateExecutionControl(cancelled)

internal fun stopExecutionControl(
    deadlineOf: () -> ComparableTimeMark?,
    scheduledOf: () -> Boolean,
    pollingOf: () -> StopPolling?,
    cancelled: () -> Boolean,
): ExecutionControl = PredicateExecutionControl(cancelled, deadlineOf, scheduledOf, pollingOf)

private class PredicateExecutionControl(
    private val cancelled: () -> Boolean,
    private val deadlineOf: () -> ComparableTimeMark? = { null },
    private val scheduledOf: () -> Boolean = { false },
    private val pollingOf: () -> StopPolling? = { null },
) : ExecutionControl() {
    override fun isCancelled(): Boolean = cancelled()
    override fun deadline() = deadlineOf()
    override val workScheduled get() = scheduledOf()
    override val stopPolling get() = pollingOf()
}

private class DeadlineExecutionControl(val end: ComparableTimeMark) : ExecutionControl(Cancellation.until(end)) {
    override fun deadline(): ComparableTimeMark = end
}
