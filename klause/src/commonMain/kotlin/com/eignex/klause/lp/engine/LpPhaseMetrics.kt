package com.eignex.klause.lp.engine

import kotlin.time.TimeSource

internal enum class LpSolvePhase { AUTHORITATIVE_IMPORT, FLOAT_ACCEPTANCE, EXACT_DUALS, CLEANUP, EXACT_LADDER }

internal data class LpPhaseMetrics(
    val phase: LpSolvePhase,
    val outcome: String,
    val nanos: Long,
    val work: Long = 0L,
    val pivots: Int = 0,
    val steps: Int = 0,
)

internal class LpPhaseTimer(private val observer: LpCertificationObserver?, private val phase: LpSolvePhase) {
    private val started = observer?.let { TimeSource.Monotonic.markNow() }

    fun finish(outcome: String, work: Long = 0L, pivots: Int = 0, steps: Int = 0) {
        val mark = started ?: return
        observer?.observePhase(
            LpPhaseMetrics(phase, outcome, mark.elapsedNow().inWholeNanoseconds, work, pivots, steps),
        )
    }
}
