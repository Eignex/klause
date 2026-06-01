package com.eignex.klause.solver

import com.eignex.kumulant.stat.summary.CountStat
import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.MaxStat
import com.eignex.kumulant.stat.summary.MeanStat
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.kumulant.stat.summary.WeightedMeanResult
import kotlinx.serialization.Serializable

/**
 * Snapshot of solver-side counters and distributions from a single solve. Carried as a
 * sidecar on [SolveResult] (and on the `MinimizeResult` variants for branch-and-bound
 * runs); populated by backends that opt in, left at its zero / empty defaults by ones
 * that don't.
 *
 * Built on kumulant's [com.eignex.kumulant.core.Result] types so two stats from
 * portfolio workers or parallel restarts can be merged by snapshotting the underlying
 * accumulators and combining the result records — same shape kumulant uses everywhere.
 *
 * Field roles:
 *
 *  - **counters**: [nodes], [fails], [restarts], [propagations], [learnedClauses] —
 *    cumulative integer counts. Most of these are kept as [SumResult] so they merge
 *    additively across runs without losing precision.
 *  - **distributions**: [peakDepth], [depthMean] — backend-shaped detail. Peak depth is
 *    the deepest decision level reached; mean depth is the average over visited nodes,
 *    capturing whether the search tree is deep-and-thin or shallow-and-wide.
 *  - **engine fingerprint**: [wallMs] (system clock) and [timedOut] (budget exhausted
 *    before a definitive verdict).
 *  - **backend tag**: [backend] — short identifier (e.g. `"backtrack"`, `"ls"`,
 *    `"logicng"`) so multi-engine sweeps can attribute numbers without ambiguity.
 */
@Serializable
data class SolveStats(
    val backend: String = "",
    val nodes: SumResult = ZERO_COUNT,
    val fails: SumResult = ZERO_COUNT,
    val restarts: SumResult = ZERO_COUNT,
    val propagations: SumResult = ZERO_COUNT,
    val learnedClauses: SumResult = ZERO_COUNT,
    val peakDepth: MaxResult = NO_MAX,
    val depthMean: WeightedMeanResult = WeightedMeanResult(totalWeights = 0.0, mean = Double.NaN),
    val wallMs: Long = 0L,
    val timedOut: Boolean = false,
) {
    /** Shared empty/default [SolveStats] instances. */
    companion object {
        internal val ZERO_COUNT: SumResult = SumResult(sum = 0.0)
        internal val NO_MAX: MaxResult = MaxResult(Double.NEGATIVE_INFINITY)

        /** Empty stats — the default for backends that don't populate. */
        val EMPTY: SolveStats = SolveStats()
    }
}

/**
 * Mutable accumulator that backends update during a solve. Snapshots into a [SolveStats]
 * record when the solve terminates.
 *
 * Concurrency: scoped to a single solve / single thread; no internal locking. Backends
 * that fan out across threads (LS portfolio, parallel restarts) should keep one sink per
 * worker and merge their [SolveStats] snapshots after.
 */
internal class SolveStatsSink(val backend: String) {
    val nodes: CountStat = CountStat()
    val fails: CountStat = CountStat()
    val restarts: CountStat = CountStat()
    val propagations: CountStat = CountStat()
    val learnedClauses: CountStat = CountStat()
    val peakDepth: MaxStat = MaxStat()
    val depthMean: MeanStat = MeanStat()

    private var startMark: kotlin.time.TimeMark? = null
    private var endElapsedMs: Long? = null
    var timedOut: Boolean = false

    fun start() {
        startMark = kotlin.time.TimeSource.Monotonic.markNow()
    }
    fun stop() {
        val mark = startMark ?: return
        endElapsedMs = mark.elapsedNow().inWholeMilliseconds
    }

    /** Convenience: call on every visited decision node so [nodes] increments and
     *  [peakDepth]/[depthMean] see the depth observation. */
    fun observeNode(depth: Int) {
        nodes.update(1.0)
        peakDepth.update(depth.toDouble())
        depthMean.update(depth.toDouble())
    }

    fun observeFail() {
        fails.update(1.0)
    }
    fun observeRestart() {
        restarts.update(1.0)
    }
    fun observePropagation(count: Long = 1L) {
        // CountStat is unweighted-count-per-call; for batched propagation events pass count
        // > 1 by looping or by switching this field to SumStat later. For now batch-as-one.
        if (count == 1L) {
            propagations.update(1.0)
        } else {
            for (i in 0 until count) propagations.update(1.0)
        }
    }
    fun observeLearn(count: Long = 1L) {
        if (count == 1L) {
            learnedClauses.update(1.0)
        } else {
            for (i in 0 until count) learnedClauses.update(1.0)
        }
    }

    /** Snapshot the current accumulator state into an immutable [SolveStats]. Wall time
     *  uses the most recent [start] / [stop] window; if [stop] hasn't been called yet, we
     *  read the elapsed time from now. */
    fun snapshot(): SolveStats {
        val elapsedMs = endElapsedMs
            ?: startMark?.elapsedNow()?.inWholeMilliseconds
            ?: 0L
        return SolveStats(
            backend = backend,
            nodes = nodes.read(),
            fails = fails.read(),
            restarts = restarts.read(),
            propagations = propagations.read(),
            learnedClauses = learnedClauses.read(),
            peakDepth = peakDepth.read(),
            depthMean = depthMean.read(),
            wallMs = elapsedMs,
            timedOut = timedOut,
        )
    }
}
