package com.eignex.klause.solver.result

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
 * sidecar on [com.eignex.klause.solver.SolveResult] (and on the `MinimizeResult` variants for branch-and-bound
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
 *  - **backend tag**: [backend] — short identifier (e.g. `"backtrack"`, `"ls"`) so multi-engine
 *    sweeps can attribute numbers without ambiguity.
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
    /** Conflict-analysis gate breakdown (#588 diagnostic). Conflicts whose analysis produced no
     *  usable clause (a NotApplicable seed). The sum of these three with [learnedClauses]
     *  (asserting + taken) ≈ the conflicts that reached analysis. */
    val caNotApplicable: SumResult = ZERO_COUNT,
    /** Conflicts whose 1UIP clause was non-asserting (>1 literal at the conflict level), so it
     *  could not be learned and the search fell back to chronological backtracking. */
    val caNonAsserting: SumResult = ZERO_COUNT,
    /** Conflicts whose asserting clause was rejected because it carried an already-true literal. */
    val caRejectedTrueLit: SumResult = ZERO_COUNT,
    /** Node LP-bounding passes that built and solved a relaxation — the denominator for the prune /
     *  fix / pivot rates. `lpPruned` alone is meaningless without knowing how many solves it took. */
    val lpSolves: SumResult = ZERO_COUNT,
    /** Nodes pruned by the LP-relaxation bound (#20): infeasible relaxation or bound ≥ incumbent.
     *  Split into [lpInfeasible] (relaxation infeasible) and the remainder (bound dominated). */
    val lpPruned: SumResult = ZERO_COUNT,
    /** Subset of [lpPruned] where the relaxation itself was infeasible (a feasibility filter, not a
     *  bound); `lpPruned − lpInfeasible` is the bound-dominated count. */
    val lpInfeasible: SumResult = ZERO_COUNT,
    /** Root-node LP relaxation objective (the live dual bound at decision level 0), or NaN when the
     *  LP never solved at the root. Against the final objective this is the integrality gap — the
     *  most direct measure of relaxation tightness. */
    val rootLpBound: Double = Double.NaN,
    /** Wall time (ms) spent inside LP bounding — the cost side of the LP ROI (benefit = prunes/fixes). */
    val lpMs: Long = 0L,
    /** Domain reductions applied by LP reduced-cost fixing (#21). */
    val lpFixed: SumResult = ZERO_COUNT,
    /** Total dual-simplex pivots across all node LP solves; drops sharply with warm-starting. */
    val lpPivots: SumResult = ZERO_COUNT,
    /** LP cuts added by separators (#22). */
    val lpCuts: SumResult = ZERO_COUNT,
    /** Non-chronological backjumps driven by an LP infeasibility (Farkas) certificate (#280). */
    val lpBackjumps: SumResult = ZERO_COUNT,
    /** Node LP solves that started from a seeded tableau (the cheapest warm start) instead of a
     *  basis reload or cold start — the hot-tableau hit rate. */
    val lpSeeded: SumResult = ZERO_COUNT,
    /** Nodes pruned by the Lagrangian bound (#23). */
    val lagrangianPruned: SumResult = ZERO_COUNT,
    /** Nodes pruned by the Cumulative energetic-reasoning check (#22/#23). */
    val energeticPruned: SumResult = ZERO_COUNT,
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
            caNotApplicable = SumResult(caNotApplicable.sum + other.caNotApplicable.sum),
            caNonAsserting = SumResult(caNonAsserting.sum + other.caNonAsserting.sum),
            caRejectedTrueLit = SumResult(caRejectedTrueLit.sum + other.caRejectedTrueLit.sum),
            lpSolves = SumResult(lpSolves.sum + other.lpSolves.sum),
            lpPruned = SumResult(lpPruned.sum + other.lpPruned.sum),
            lpInfeasible = SumResult(lpInfeasible.sum + other.lpInfeasible.sum),
            // Same root across workers, so the tightest finite bound represents it; NaN defers.
            rootLpBound = when {
                rootLpBound.isNaN() -> other.rootLpBound
                other.rootLpBound.isNaN() -> rootLpBound
                else -> maxOf(rootLpBound, other.rootLpBound)
            },
            lpMs = lpMs + other.lpMs,
            lpFixed = SumResult(lpFixed.sum + other.lpFixed.sum),
            lpPivots = SumResult(lpPivots.sum + other.lpPivots.sum),
            lpCuts = SumResult(lpCuts.sum + other.lpCuts.sum),
            lpBackjumps = SumResult(lpBackjumps.sum + other.lpBackjumps.sum),
            lpSeeded = SumResult(lpSeeded.sum + other.lpSeeded.sum),
            lagrangianPruned = SumResult(lagrangianPruned.sum + other.lagrangianPruned.sum),
            energeticPruned = SumResult(energeticPruned.sum + other.energeticPruned.sum),
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
    val caNotApplicable: CountStat = CountStat()
    val caNonAsserting: CountStat = CountStat()
    val caRejectedTrueLit: CountStat = CountStat()
    val lpSolves: CountStat = CountStat()
    val lpPruned: CountStat = CountStat()
    val lpInfeasible: CountStat = CountStat()
    val lpFixed: CountStat = CountStat()
    val lpPivots: CountStat = CountStat()
    val lpCuts: CountStat = CountStat()
    val lpBackjumps: CountStat = CountStat()
    val lpSeeded: CountStat = CountStat()
    val lagrangianPruned: CountStat = CountStat()
    val energeticPruned: CountStat = CountStat()
    val peakDepth: MaxStat = MaxStat()
    val depthMean: MeanStat = MeanStat()

    private var startMark: TimeMark? = null
    private var endElapsedMs: Long? = null

    /** Root-node LP bound (NaN until the LP solves at decision level 0); accumulated LP wall time. */
    private var rootLpBound: Double = Double.NaN
    private var lpMs: Long = 0L
    private var lpClock: TimeMark? = null
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
    fun observeCaNotApplicable() {
        caNotApplicable.update(1.0)
    }
    fun observeCaNonAsserting() {
        caNonAsserting.update(1.0)
    }
    fun observeCaRejectedTrueLit() {
        caRejectedTrueLit.update(1.0)
    }

    /** One node LP-bounding pass that built and solved a relaxation (the rate denominator). */
    fun observeLpSolve() {
        lpSolves.update(1.0)
    }

    /** A node whose subtree was cut by the LP-relaxation bound (#20) because its bound dominated the
     *  incumbent (or an LP-derived deduction emptied a domain). */
    fun observeLpPrune() {
        lpPruned.update(1.0)
    }

    /** A node pruned because the LP relaxation was infeasible — counted in both [lpPruned] (the
     *  total) and [lpInfeasible] (the feasibility-filter share). */
    fun observeLpInfeasiblePrune() {
        lpPruned.update(1.0)
        lpInfeasible.update(1.0)
    }

    /** Record the root-node (decision level 0) LP relaxation objective; last write at the root wins,
     *  so it reflects the strengthened post-cut bound. Ignored off the root or for a non-finite value. */
    fun observeRootLpBound(decisionLevel: Int, value: Double) {
        if (decisionLevel == 0 && value.isFinite()) rootLpBound = value
    }

    /** Bracket LP-bounding wall time: [lpClockStart] then [lpClockStop] adds the interval to [lpMs]. */
    fun lpClockStart() {
        lpClock = Monotonic.markNow()
    }
    fun lpClockStop() {
        val mark = lpClock ?: return
        lpMs += mark.elapsedNow().inWholeMilliseconds
        lpClock = null
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

    /** A non-chronological backjump driven by an LP infeasibility certificate (#280). */
    fun observeLpBackjump() {
        lpBackjumps.update(1.0)
    }

    /** A node LP solve that started from a seeded tableau instead of a basis/cold reload. */
    fun observeLpSeeded() {
        lpSeeded.update(1.0)
    }

    /** A node whose subtree was cut by the Lagrangian bound (#23). */
    fun observeLagrangianPrune() {
        lagrangianPruned.update(1.0)
    }

    /** A node whose subtree was cut by the Cumulative energetic check (#22/#23). */
    fun observeEnergeticPrune() {
        energeticPruned.update(1.0)
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
            caNotApplicable = caNotApplicable.read(),
            caNonAsserting = caNonAsserting.read(),
            caRejectedTrueLit = caRejectedTrueLit.read(),
            lpSolves = lpSolves.read(),
            lpPruned = lpPruned.read(),
            lpInfeasible = lpInfeasible.read(),
            rootLpBound = rootLpBound,
            lpMs = lpMs,
            lpFixed = lpFixed.read(),
            lpPivots = lpPivots.read(),
            lpCuts = lpCuts.read(),
            lpBackjumps = lpBackjumps.read(),
            lpSeeded = lpSeeded.read(),
            lagrangianPruned = lagrangianPruned.read(),
            energeticPruned = energeticPruned.read(),
            peakDepth = peakDepth.read(),
            depthMean = depthMean.read(),
            wallMs = elapsedMs,
            timedOut = timedOut,
        )
    }
}
