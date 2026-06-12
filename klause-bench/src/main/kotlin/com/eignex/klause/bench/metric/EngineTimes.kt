package com.eignex.klause.bench.metric

import com.eignex.klause.solver.result.SearchEvent
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stat.summary.ArgMinStat
import com.eignex.kumulant.stat.summary.MinStat

/**
 * Engine-side incumbent timing recorded straight off the [SearchEvent] seam (#140). The harness
 * otherwise stamps incumbents when it pulls them off the stream, which folds in consumer latency —
 * in portfolio mode a whole thread + queue bridge. Events fire inside the engine at the moment of
 * improvement; portfolio workers fire concurrently, hence the [Concurrency.Strict] accumulators:
 * time-to-first is a plain min over incumbent timestamps, time-to-best the argmin of objective
 * over time.
 */
internal class EngineTimes {
    private val t0 = System.nanoTime()
    private val first = MinStat(Concurrency.Strict)
    private val best = ArgMinStat(Concurrency.Strict)

    val listener: (SearchEvent) -> Unit = { e ->
        if (e is SearchEvent.Incumbent) {
            val at = System.nanoTime() - t0
            first.update(at.toDouble())
            best.update(e.objective, timestampNanos = at)
        }
    }

    /** Milliseconds to the first incumbent, or -1 when none arrived. */
    val firstMs: Long
        get() = first.read().min.let { if (it.isFinite()) (it / NANOS_PER_MILLI).toLong() else -1L }

    /** Milliseconds to the best (minimum-objective) incumbent, or -1 when none arrived. */
    val bestMs: Long
        get() = best.read().let { if (it.min.isFinite()) it.atTimestampNanos / NANOS_PER_MILLI.toLong() else -1L }

    private companion object {
        const val NANOS_PER_MILLI = 1e6
    }
}
