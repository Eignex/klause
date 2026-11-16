package com.eignex.klause.solver

import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.unboundStopControl
import com.eignex.klause.util.workScheduled
import kotlin.time.ComparableTimeMark

internal fun ExecutionControl.lifetimeCancellation(): ExecutionControl =
    (unboundStopControl() as? ScopedCancellation)?.lifetime ?: ExecutionControl.Never

internal fun ExecutionControl.forInvocation(invocation: ExecutionControl): ExecutionControl {
    val scope = unboundStopControl() as? ScopedCancellation ?: return invocation
    val owner = if (invocation.accounting != null) invocation else scope.owner
    return ScopedCancellation(invocation, scope.lifetime, owner)
}

internal fun ExecutionControl.withLifetime(lifetime: ExecutionControl): ExecutionControl {
    val scope = unboundStopControl() as? ScopedCancellation
    val invocation = scope?.invocation ?: this
    val owner = if (accounting != null) scope?.owner ?: this else lifetime
    val retained = lifetimeCancellation().withAccounting(owner) or lifetime
    return ScopedCancellation(invocation, retained, owner)
}

private class ScopedCancellation(
    val invocation: ExecutionControl,
    val lifetime: ExecutionControl,
    val owner: ExecutionControl,
) : ExecutionControl(owner) {
    override val stopPolling get() = invocation.stopPolling ?: lifetime.stopPolling
    override val workScheduled: Boolean get() = invocation.workScheduled() || lifetime.workScheduled()
    override fun isCancelled(): Boolean = lifetime() || invocation()
    override fun deadline(): ComparableTimeMark? = listOfNotNull(lifetime.deadline(), invocation.deadline()).minOrNull()
}
