package com.eignex.klause.solver

import com.eignex.klause.util.ArithmeticCancellation
import com.eignex.klause.util.ArithmeticWorkSource
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SchedulingCancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.arithmeticSource
import com.eignex.klause.util.withCheckpoint
import com.eignex.klause.util.workScheduled
import kotlin.time.ComparableTimeMark

internal fun Cancellation.lifetimeCancellation(): Cancellation =
    (this as? ScopedCancellation)?.lifetime ?: Cancellation.Never

internal fun Cancellation.forInvocation(invocation: Cancellation): Cancellation {
    val lifetime = lifetimeCancellation()
    return if (lifetime === Cancellation.Never) invocation else ScopedCancellation(invocation, lifetime)
}

internal fun Cancellation.withLifetime(lifetime: Cancellation): Cancellation = ScopedCancellation(
    (this as? ScopedCancellation)?.invocation ?: this, lifetimeCancellation() or lifetime,
)

private class ScopedCancellation(val invocation: Cancellation, val lifetime: Cancellation) :
    SchedulingCancellation, ArithmeticCancellation {
    override val workScheduled: Boolean get() = invocation.workScheduled() || lifetime.workScheduled()
    override fun isCancelled(): Boolean = lifetime() || invocation()
    override fun deadline(): ComparableTimeMark? = listOfNotNull(lifetime.deadline(), invocation.deadline()).minOrNull()
    override fun workMeter(): WorkMeter? = invocation.workMeter() ?: lifetime.workMeter()
    override fun arithmeticSource(): ArithmeticWorkSource? {
        val invocationSource = invocation.arithmeticSource() ?: return null
        val lifetimeSource = lifetime.arithmeticSource() ?: return null
        val accounting = if (invocation.workMeter() != null) invocationSource else lifetimeSource
        return accounting.withCheckpoint(this::isCancelled)
    }
}
