package com.eignex.klause.solver

import com.eignex.kumulant.stat.summary.CountStat
import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.MaxStat
import com.eignex.kumulant.stat.summary.MeanStat
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.kumulant.stat.summary.WeightedMeanResult
import kotlinx.serialization.Serializable
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

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
    /** Clauses conflict analysis re-derived identically — a livelock indicator when large. */
    val relearned: SumResult = ZERO_COUNT,
    /** Nodes pruned by the LP-relaxation bound (#20): infeasible relaxation or bound ≥ incumbent. */
    val lpPruned: SumResult = ZERO_COUNT,
    /** Domain reductions applied by LP reduced-cost fixing (#21). */
    val lpFixed: SumResult = ZERO_COUNT,
    /** Total dual-simplex pivots across all node LP solves; drops sharply with warm-starting. */
    val lpPivots: SumResult = ZERO_COUNT,
    /** LP cuts added by separators (#22). */
    val lpCuts: SumResult = ZERO_COUNT,
    val peakDepth: MaxResult = NO_MAX,
    val depthMean: WeightedMeanResult = WeightedMeanResult(totalWeights = 0.0, mean = Double.NaN),
    val wallMs: Long = 0L,
    val timedOut: Boolean = false,
) {
    /**
     * Combine two run snapshots: counters add, peak depth maxes, depth means weight-combine,
     * wall time takes the max (runs are concurrent, not sequential), and timed-out ORs.
     * [EMPTY] is the identity. Backend tags survive when equal and degrade to `"mixed"`
     * otherwise — the portfolio folds heterogeneous worker snapshots through this.
     */
    fun mergedWith(other: SolveStats): SolveStats {
        if (this == EMPTY) return other
        if (other == EMPTY) return this
        val weights = depthMean.totalWeights + other.depthMean.totalWeights
        val mean = when {
            depthMean.totalWeights == 0.0 -> other.depthMean.mean

            other.depthMean.totalWeights == 0.0 -> depthMean.mean

            else ->
                (depthMean.mean * depthMean.totalWeights + other.depthMean.mean * other.depthMean.totalWeights) /
                    weights
        }
        return SolveStats(
            backend = if (backend == other.backend) backend else "mixed",
            nodes = SumResult(nodes.sum + other.nodes.sum),
            fails = SumResult(fails.sum + other.fails.sum),
            restarts = SumResult(restarts.sum + other.restarts.sum),
            propagations = SumResult(propagations.sum + other.propagations.sum),
            learnedClauses = SumResult(learnedClauses.sum + other.learnedClauses.sum),
            lpPruned = SumResult(lpPruned.sum + other.lpPruned.sum),
            lpFixed = SumResult(lpFixed.sum + other.lpFixed.sum),
            lpPivots = SumResult(lpPivots.sum + other.lpPivots.sum),
            lpCuts = SumResult(lpCuts.sum + other.lpCuts.sum),
            peakDepth = MaxResult(maxOf(peakDepth.max, other.peakDepth.max)),
            depthMean = WeightedMeanResult(totalWeights = weights, mean = mean),
            wallMs = maxOf(wallMs, other.wallMs),
            timedOut = timedOut || other.timedOut,
        )
    }

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
    val relearned: CountStat = CountStat()
    val lpPruned: CountStat = CountStat()
    val lpFixed: CountStat = CountStat()
    val lpPivots: CountStat = CountStat()
    val lpCuts: CountStat = CountStat()
    val peakDepth: MaxStat = MaxStat()
    val depthMean: MeanStat = MeanStat()

    private var startMark: TimeMark? = null
    private var endElapsedMs: Long? = null
    var timedOut: Boolean = false

    fun start() {
        startMark = Monotonic.markNow()
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
            repeat(count.toInt()) { propagations.update(1.0) }
        }
    }
    fun observeLearn(count: Long = 1L) {
        if (count == 1L) {
            learnedClauses.update(1.0)
        } else {
            repeat(count.toInt()) { learnedClauses.update(1.0) }
        }
    }

    /** Conflict analysis re-derived a clause identical to one it already produced — a
     *  livelock indicator when it grows large relative to [learnedClauses]. */
    fun observeRelearn() {
        relearned.update(1.0)
    }

    /** A node whose subtree was cut by the LP-relaxation bound (#20). */
    fun observeLpPrune() {
        lpPruned.update(1.0)
    }

    /** One domain reduction applied by LP reduced-cost fixing (#21). */
    fun observeLpFix() {
        lpFixed.update(1.0)
    }

    /** Record [count] dual-simplex pivots from one node LP solve. */
    fun observeLpPivots(count: Int) {
        repeat(count) { lpPivots.update(1.0) }
    }

    /** Record [count] cuts added by separators (#22). */
    fun observeLpCuts(count: Int) {
        repeat(count) { lpCuts.update(1.0) }
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
            relearned = relearned.read(),
            lpPruned = lpPruned.read(),
            lpFixed = lpFixed.read(),
            lpPivots = lpPivots.read(),
            lpCuts = lpCuts.read(),
            peakDepth = peakDepth.read(),
            depthMean = depthMean.read(),
            wallMs = elapsedMs,
            timedOut = timedOut,
        )
    }
}
