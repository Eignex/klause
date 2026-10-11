package com.eignex.klause.solver

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
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

private class ScopedCancellation(val invocation: Cancellation, val lifetime: Cancellation) : Cancellation {
    override fun isCancelled(): Boolean = lifetime() || invocation()
    override fun deadline(): ComparableTimeMark? = listOfNotNull(lifetime.deadline(), invocation.deadline()).minOrNull()
    override fun workMeter(): WorkMeter? = invocation.workMeter() ?: lifetime.workMeter()
}
