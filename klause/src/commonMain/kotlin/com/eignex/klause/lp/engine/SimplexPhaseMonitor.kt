package com.eignex.klause.lp.engine

import kotlin.time.TimeSource

internal abstract class SimplexPhaseMonitor {
    abstract val trace: SimplexProgressTrace?

    abstract fun sample(phase: SimplexPhase, beta: DoubleArray)

    abstract suspend fun checkpoint(progress: SolveProgress)

    inline fun <T> profiled(phase: SimplexProfilePhase, block: () -> T): T {
        val trace = trace?.takeIf { it.profile } ?: return block()
        val started = TimeSource.Monotonic.markNow()
        val checkpoints = trace.checkpointNanos
        try {
            return block()
        } finally {
            val elapsed = started.elapsedNow().inWholeNanoseconds - (trace.checkpointNanos - checkpoints)
            trace.recordPhase(phase, maxOf(0L, elapsed))
        }
    }
}
