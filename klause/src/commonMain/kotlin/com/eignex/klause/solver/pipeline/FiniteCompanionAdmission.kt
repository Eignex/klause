package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class FiniteCompanionAdmission(
    private val cancellation: Cancellation,
    activeMillis: Long,
    constructionMillis: Long,
    private val capture: (Cancellation) -> SourceProofContext?,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private val deadline = requireNotNull(cancellation.deadline())
    private val active = activeMillis.milliseconds
    private val construction = constructionMillis.milliseconds
    private val pairLifetime = active * 2
    val initiallyFunded = !cancellation.isCancelled() && remaining() > pairLifetime
    private var attempted = false
    private var context: SourceProofContext? = null
    private var elapsedNanos = 0L
    private var startDeclines = 0L
    var declineReason: TerminationReason = TerminationReason.BudgetExhausted
        private set

    init { require(active > Duration.ZERO && construction > Duration.ZERO) }

    fun contextForStart(global: Cancellation, sliceMillis: Long): SourceProofContext? {
        if (!initiallyFunded || cancellation.isCancelled() || remaining() < active) return decline()
        if (!attempted) {
            val available = remaining() - pairLifetime
            if (available <= Duration.ZERO) return decline()
            attempted = true
            val allowance = minOf(available, construction, sliceMillis.coerceAtLeast(0).milliseconds)
            val localDeadline = minOf(deadline - pairLifetime, deadline - (remaining() - allowance))
            val limited = Cancellation.until(localDeadline) or cancellation or global
            val started = clock.markNow()
            val captured = try { capture(limited) } finally {
                elapsedNanos = started.elapsedNow().inWholeNanoseconds
            }
            if (!limited.isCancelled() && remaining() >= pairLifetime) context = captured
            if (context == null && !limited.isCancelled()) declineReason = TerminationReason.Unsupported
        }
        return context ?: decline()
    }

    fun stages(): Map<String, Long> = mapOf(
        "finiteCompanionAdmissionDeclined" to if (initiallyFunded) 0L else 1L,
        "finiteCompanionStartDeclines" to startDeclines,
        "finiteCompanionPreparationAttempts" to if (attempted) 1L else 0L,
        "finiteCompanionPreparationNs" to elapsedNanos,
        "finiteCompanionPreparationReady" to if (context != null) 1L else 0L,
        "finiteCompanionPreparationUnavailable" to if (attempted && context == null) 1L else 0L,
    )

    private fun remaining(): Duration = -deadline.elapsedNow()

    private fun decline(): SourceProofContext? {
        startDeclines++
        return null
    }
}
