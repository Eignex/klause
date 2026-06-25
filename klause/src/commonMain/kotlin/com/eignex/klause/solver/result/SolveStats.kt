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
    /** Max sparse-LU fill ratio `(nnz L+U)/nnz B` over all factorizations (#27); >1 = fill-in growth. */
    val lpLuMaxFill: MaxResult = NO_MAX,
    /** Max sparse-LU density `(nnz L+U)/m²`; approaching 1.0 means the LU filled in to effectively dense. */
    val lpLuMaxDensity: MaxResult = NO_MAX,
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
    /** Local-search moves applied (bool flips / int sets / compounds + restart work units) — the LS
     *  analogue of [nodes], and the denominator for moves-per-second. Zero for complete backends. */
    val moves: SumResult = ZERO_COUNT,
    /** Local-search descents that hit a local optimum / plateau and triggered a restart — the stall rate
     *  against [moves] tells whether the search is making progress or thrashing. */
    val stalls: SumResult = ZERO_COUNT,
    /** Wall ms from solve start to when the best incumbent was found, or -1 when no incumbent was
     *  established. Against [wallMs] this is the anytime profile: a small ratio means the search found its
     *  best early and spent the rest stuck. */
    val timeToBestMs: Long = -1L,
    /** Objective value at the best incumbent the LS engine reached, or NaN when none was feasible. */
    val incumbentObjective: Double = Double.NaN,
    /** Total constraint violation (LS cost) at the best incumbent: 0 once feasible, else the lowest
     *  residual cost reached — how close an infeasible run got. NaN when unpopulated. */
    val incumbentViolation: Double = Double.NaN,
    val peakDepth: MaxResult = NO_MAX,
    val depthMean: WeightedMeanResult = WeightedMeanResult(totalWeights = 0.0, mean = Double.NaN),
    val wallMs: Long = 0L,
    val timedOut: Boolean = false,
    /** Presolve outcome, set by the CLI after presolve runs (null when presolve was off / a no-op).
     *  Surfaced under `-s` as a terse summary — see [PresolveStats]. */
    val presolve: PresolveStats? = null,
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
        fun sum(field: SolveStats.() -> SumResult) = SumResult(field().sum + other.field().sum)
        fun max(field: SolveStats.() -> MaxResult) = MaxResult(maxOf(field().max, other.field().max))
        return SolveStats(
            backend = if (backend == other.backend) backend else "mixed",
            nodes = sum { nodes },
            fails = sum { fails },
            restarts = sum { restarts },
            propagations = sum { propagations },
            learnedClauses = sum { learnedClauses },
            caNotApplicable = sum { caNotApplicable },
            caNonAsserting = sum { caNonAsserting },
            caRejectedTrueLit = sum { caRejectedTrueLit },
            lpSolves = sum { lpSolves },
            lpPruned = sum { lpPruned },
            lpInfeasible = sum { lpInfeasible },
            // Same root across workers, so the tightest finite bound represents it; NaN defers.
            rootLpBound = naNDeferring(rootLpBound, other.rootLpBound, ::maxOf),
            lpMs = lpMs + other.lpMs,
            lpFixed = sum { lpFixed },
            lpPivots = sum { lpPivots },
            lpLuMaxFill = max { lpLuMaxFill },
            lpLuMaxDensity = max { lpLuMaxDensity },
            lpCuts = sum { lpCuts },
            lpBackjumps = sum { lpBackjumps },
            lpSeeded = sum { lpSeeded },
            lagrangianPruned = sum { lagrangianPruned },
            energeticPruned = sum { energeticPruned },
            moves = sum { moves },
            stalls = sum { stalls },
            // Earliest time-to-best across workers (the portfolio reports the first to reach its best);
            // -1 sentinels defer to any real reading.
            timeToBestMs = when {
                timeToBestMs < 0L -> other.timeToBestMs
                other.timeToBestMs < 0L -> timeToBestMs
                else -> minOf(timeToBestMs, other.timeToBestMs)
            },
            // Keep the incumbent fingerprint from whichever worker got closer to feasibility (lower
            // violation); direction-agnostic so it's sound for both minimise and maximise. NaN defers.
            incumbentObjective = pickByViolation(
                incumbentViolation,
                incumbentObjective,
                other.incumbentViolation,
                other.incumbentObjective,
            ),
            incumbentViolation = naNDeferring(incumbentViolation, other.incumbentViolation, ::minOf),
            peakDepth = max { peakDepth },
            depthMean = mergeDepthMean(depthMean, other.depthMean),
            wallMs = maxOf(wallMs, other.wallMs),
            timedOut = timedOut || other.timedOut,
            presolve = presolve ?: other.presolve,
        )
    }

    /** Shared empty/default [SolveStats] instances. */
    companion object {
        internal val ZERO_COUNT: SumResult = SumResult(sum = 0.0)
        internal val NO_MAX: MaxResult = MaxResult(Double.NEGATIVE_INFINITY)

        /** Of two (violation, objective) pairs, return the objective paired with the lower violation
         *  (closer to feasible); NaN violation defers to the other side, ties keep the left objective. */
        private fun pickByViolation(violA: Double, objA: Double, violB: Double, objB: Double): Double = when {
            violA.isNaN() -> objB
            violB.isNaN() -> objA
            violB < violA -> objB
            else -> objA
        }

        /** Combine two readings under [reduce], treating NaN as "unpopulated" so it defers to a real value. */
        private inline fun naNDeferring(a: Double, b: Double, reduce: (Double, Double) -> Double): Double = when {
            a.isNaN() -> b
            b.isNaN() -> a
            else -> reduce(a, b)
        }

        /** Weight-combine two depth means; an empty side (zero weight) defers to the other. */
        private fun mergeDepthMean(a: WeightedMeanResult, b: WeightedMeanResult): WeightedMeanResult {
            val weights = a.totalWeights + b.totalWeights
            val mean = when {
                a.totalWeights == 0.0 -> b.mean
                b.totalWeights == 0.0 -> a.mean
                else -> (a.mean * a.totalWeights + b.mean * b.totalWeights) / weights
            }
            return WeightedMeanResult(totalWeights = weights, mean = mean)
        }

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
    val lpLuMaxFill: MaxStat = MaxStat()
    val lpLuMaxDensity: MaxStat = MaxStat()
    val lpCuts: CountStat = CountStat()
    val lpBackjumps: CountStat = CountStat()
    val lpSeeded: CountStat = CountStat()
    val lagrangianPruned: CountStat = CountStat()
    val energeticPruned: CountStat = CountStat()
    val peakDepth: MaxStat = MaxStat()
    val depthMean: MeanStat = MeanStat()

    // Plain accumulators rather than CountStat: move counts reach the millions, so per-event
    // CountStat.update would be pure overhead — the LS loop sets these in bulk.
    private var lsMoves: Long = 0L
    private var lsRestarts: Long = 0L
    private var lsStalls: Long = 0L
    private var lsTimeToBestMs: Long = -1L
    private var lsIncumbentObjective: Double = Double.NaN
    private var lsIncumbentViolation: Double = Double.NaN

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

    /** Record one node LP solve's sparse-LU fill ratio and density (#27 sparsity audit). */
    fun observeLpLuFill(fill: Double, density: Double) {
        if (fill > 0.0) lpLuMaxFill.update(fill)
        if (density > 0.0) lpLuMaxDensity.update(density)
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

    /** Record the LS engine's move / restart / stall totals in one call at loop exit; cheaper than
     *  per-event updates when moves run to the millions. */
    fun recordLsWork(moves: Long, restarts: Long, stalls: Long) {
        lsMoves = moves
        lsRestarts = restarts
        lsStalls = stalls
    }

    /** Record the LS incumbent fingerprint: its objective (NaN if never feasible), its residual
     *  violation (0 once feasible), and the wall ms at which it was found (-1 if no incumbent). */
    fun recordLsIncumbent(objective: Double, violation: Double, foundAtMs: Long) {
        lsIncumbentObjective = objective
        lsIncumbentViolation = violation
        lsTimeToBestMs = foundAtMs
    }

    /** Elapsed ms since [start] — used by the LS loop to stamp time-to-best as incumbents land. */
    fun elapsedMs(): Long = startMark?.elapsedNow()?.inWholeMilliseconds ?: 0L

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
            // LS folds its restart count in here (it never touches the CountStat path); complete
            // backends report their own restarts CountStat.
            restarts = if (lsRestarts > 0L) SumResult(lsRestarts.toDouble()) else restarts.read(),
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
            lpLuMaxFill = lpLuMaxFill.read(),
            lpLuMaxDensity = lpLuMaxDensity.read(),
            lpCuts = lpCuts.read(),
            lpBackjumps = lpBackjumps.read(),
            lpSeeded = lpSeeded.read(),
            lagrangianPruned = lagrangianPruned.read(),
            energeticPruned = energeticPruned.read(),
            moves = SumResult(lsMoves.toDouble()),
            stalls = SumResult(lsStalls.toDouble()),
            timeToBestMs = lsTimeToBestMs,
            incumbentObjective = lsIncumbentObjective,
            incumbentViolation = lsIncumbentViolation,
            peakDepth = peakDepth.read(),
            depthMean = depthMean.read(),
            wallMs = elapsedMs,
            timedOut = timedOut,
        )
    }
}

/**
 * What the LP-relaxation harvest contributed during presolve, summed over the presolve↔harvest fixpoint
 * rounds — isolating the LP's effect from the combinatorial passes (whose net effect the surrounding
 * [PresolveStats] counts conflate). [rootInfeasible] flags that the root relaxation was certified
 * infeasible (the whole problem is UNSAT); [boundsShaved] counts integer variables whose bounds the LP
 * tightened; [objectiveLbRaised] flags an objective lower-bound harvest; [constraintsRemoved] counts rows
 * the LP proved redundant (max/min over the others); [equalitiesAdded] counts differences the LP proved
 * pinned to a constant and emitted as `=` for affine elimination.
 */
@Serializable
data class LpHarvestReport(
    val rootInfeasible: Boolean = false,
    val boundsShaved: Int = 0,
    val objectiveLbRaised: Boolean = false,
    val constraintsRemoved: Int = 0,
    val equalitiesAdded: Int = 0,
    /** Built root relaxation columns — part of the size the cost gate weighs (0 when none was built). */
    val relaxationCols: Int = 0,
    /** Built root relaxation rows. */
    val relaxationRows: Int = 0,
    /** Built root relaxation nonzeros. */
    val relaxationNnz: Int = 0,
    /** The harvest was enabled but the relaxation size exceeded the budget, so it ran no probe work. */
    val skipped: Boolean = false,
) {
    /** True when the harvest neither acted nor was size-skipped — drop the field rather than report it. */
    val isEmpty: Boolean
        get() = !rootInfeasible && boundsShaved == 0 && !objectiveLbRaised &&
            constraintsRemoved == 0 && equalitiesAdded == 0 && !skipped

    /** Accumulate two rounds' reports: counts add, flags or together, the relaxation size takes the
     *  larger (the round that paid the most). */
    operator fun plus(o: LpHarvestReport): LpHarvestReport = LpHarvestReport(
        rootInfeasible || o.rootInfeasible,
        boundsShaved + o.boundsShaved,
        objectiveLbRaised || o.objectiveLbRaised,
        constraintsRemoved + o.constraintsRemoved,
        equalitiesAdded + o.equalitiesAdded,
        maxOf(relaxationCols, o.relaxationCols),
        maxOf(relaxationRows, o.relaxationRows),
        maxOf(relaxationNnz, o.relaxationNnz),
        skipped || o.skipped,
    )
}

/**
 * Terse presolve outcome for `-s`: just enough to show presolve did something and which techniques —
 * deliberately small so it doesn't dominate the solve counters (the verbose readout is
 * `dry-run-presolve`). [passes] are the ids of the presolve passes that changed the problem;
 * [constraintsRemoved] is the net drop in factor count; [infeasible] flags presolve-proven UNSAT;
 * [lpHarvest] breaks out the LP harvest's own contribution when it fired.
 */
@Serializable
data class PresolveStats(
    val passes: List<String> = emptyList(),
    val constraintsRemoved: Int = 0,
    val infeasible: Boolean = false,
    val lpHarvest: LpHarvestReport? = null,
)
