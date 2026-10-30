package com.eignex.klause.lp.engine

import kotlin.time.TimeSource

internal enum class LpSolvePhase {
    CLAIM_CONSUMPTION,
    CLAIM_BOUND,
    CLAIM_CONTRADICTION,
    CLAIM_FEASIBLE_COMPLETION,
    CLAIM_ATTAINED_OPTIMUM,
    AUTHORITATIVE_IMPORT,
    SOURCE_FLOAT_ACCEPTANCE,
    FLOAT_ACCEPTANCE,
    EXACT_DUALS,
    CLEANUP,
    EXACT_LADDER,
    EXACT_POINT_RECOVERY,
    BOUND_CERTIFICATE,
    CONFLICT_CERTIFICATE,
    ROOT_QUERY,
    ROOT_LAYOUT_PREPARATION,
    NODE_DEDUCTION,
    NODE_ROW_FIXING,
    NODE_PRIMARY_FIXING,
    NODE_SECONDARY_FIXING,
    NODE_TERTIARY_FIXING,
}

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
