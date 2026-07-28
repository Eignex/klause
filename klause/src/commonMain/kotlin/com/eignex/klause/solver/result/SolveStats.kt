package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.kumulant.stat.summary.WeightedMeanResult
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

/** Zero-count default for [SumResult] stat fields; shared across the stat records in this package. */
internal val ZERO_COUNT: SumResult = SumResult(sum = 0.0)

/** Empty-max default for [MaxResult] stat fields (no observation yet). */
internal val NO_MAX: MaxResult = MaxResult(Double.NEGATIVE_INFINITY)

/** Combine two readings under [reduce], treating NaN as "unpopulated" so it defers to a real value. */
internal inline fun naNDeferring(a: Double, b: Double, reduce: (Double, Double) -> Double): Double = when {
    a.isNaN() -> b
    b.isNaN() -> a
    else -> reduce(a, b)
}

/** Of two (violation, objective) pairs, return the objective paired with the lower violation (closer to
 *  feasible); NaN violation defers to the other side, ties keep the left objective. */
internal fun pickByViolation(violA: Double, objA: Double, violB: Double, objB: Double): Double = when {
    violA.isNaN() -> objB
    violB.isNaN() -> objA
    violB < violA -> objB
    else -> objA
}

/** Weight-combine two depth means; an empty side (zero weight) defers to the other. */
internal fun mergeDepthMean(a: WeightedMeanResult, b: WeightedMeanResult): WeightedMeanResult {
    val weights = a.totalWeights + b.totalWeights
    val mean = when {
        a.totalWeights == 0.0 -> b.mean
        b.totalWeights == 0.0 -> a.mean
        else -> (a.mean * a.totalWeights + b.mean * b.totalWeights) / weights
    }
    return WeightedMeanResult(totalWeights = weights, mean = mean)
}

/**
 * Snapshot of solver-side counters and distributions from a single solve. Carried as a sidecar on
 * [com.eignex.klause.solver.SolveResult] (and on the `MinimizeResult` variants for branch-and-bound
 * runs); populated by backends that opt in, left at its empty defaults by ones that don't.
 *
 * A composition of per-concern records — the run envelope ([run]), tree search ([search]),
 * conflict analysis ([ca]), LP bounding ([lp]), scheduling bounds ([scheduling]), local search ([ls]),
 * and the optional [presolve] summary — each owning its own fields and merge. Built on kumulant's
 * result types so two snapshots (portfolio workers / parallel restarts) merge additively; [mergedWith]
 * folds them by delegating to each record.
 */
data class SolveStats(
    /** Run envelope: backend tag, wall time, timed-out flag. See [RunStats]. */
    val run: RunStats = RunStats(),
    /** Core tree-search counters (nodes, fails, restarts, propagations, learned clauses, depth). See [SearchStats]. */
    val search: SearchStats = SearchStats(),
    /** Conflict-analysis gate breakdown (#588). See [ConflictAnalysisStats]. */
    val ca: ConflictAnalysisStats = ConflictAnalysisStats(),
    /** LP-relaxation bounding counters (#20-#280). See [LpStats]. */
    val lp: LpStats = LpStats(),
    /** Scheduling bound prunes (Lagrangian / energetic). See [SchedulingStats]. */
    val scheduling: SchedulingStats = SchedulingStats(),
    /** Local-search telemetry (moves, stalls, incumbent). See [LocalSearchStats]. */
    val ls: LocalSearchStats = LocalSearchStats(),
    /** Presolve outcome, set by the CLI after presolve runs (null when presolve was off / a no-op).
     *  Surfaced under `-s` as a terse summary — see [PresolveStats]. */
    val presolve: PresolveStats? = null,
) {
    /**
     * Combine two run snapshots by delegating to each record's own merge — counters add, maxes max,
     * depth means weight-combine, wall time takes the max, backend degrades to `"mixed"` on mismatch,
     * and `presolve` keeps the first non-null. [EMPTY] is the identity. The portfolio folds
     * heterogeneous worker snapshots through this.
     */
    fun mergedWith(other: SolveStats): SolveStats {
        if (this == EMPTY) return other
        if (other == EMPTY) return this
        return SolveStats(
            run = run.mergedWith(other.run),
            search = search.mergedWith(other.search),
            ca = ca.mergedWith(other.ca),
            lp = lp.mergedWith(other.lp),
            scheduling = scheduling.mergedWith(other.scheduling),
            ls = ls.mergedWith(other.ls),
            presolve = presolve ?: other.presolve,
        )
    }

    /** Shared default [SolveStats]. */
    companion object {
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
    val search: SearchStatsSink = SearchStatsSink()
    val ca: ConflictAnalysisStatsSink = ConflictAnalysisStatsSink()

    /** LP-bounding counters + timing; observe via `sink.lp.observeSolve()`, etc. */
    val lp: LpStatsSink = LpStatsSink()
    val scheduling: SchedulingStatsSink = SchedulingStatsSink()
    val ls: LocalSearchStatsSink = LocalSearchStatsSink()

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

    /** Elapsed ms since [start] — used by the LS loop to stamp time-to-best as incumbents land. */
    fun elapsedMs(): Long = startMark?.elapsedNow()?.inWholeMilliseconds ?: 0L

    /** Snapshot the current accumulator state into an immutable [SolveStats]. Wall time uses the most
     *  recent [start] / [stop] window; if [stop] hasn't been called yet, elapsed is read from now. */
    fun snapshot(): SolveStats {
        val elapsedMs = endElapsedMs
            ?: startMark?.elapsedNow()?.inWholeMilliseconds
            ?: 0L
        // LS folds its restart count into the shared search restarts field (it never touches the search
        // CountStat); complete backends report their own.
        val searchStats = search.snapshot().let {
            if (ls.restarts > 0L) it.copy(restarts = SumResult(ls.restarts.toDouble())) else it
        }
        return SolveStats(
            run = RunStats(backend = backend, wallMs = elapsedMs, timedOut = timedOut),
            search = searchStats,
            ca = ca.snapshot(),
            lp = lp.snapshot(),
            scheduling = scheduling.snapshot(),
            ls = ls.snapshot(),
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
data class PresolveStats(
    val passes: List<String> = emptyList(),
    val constraintsRemoved: Int = 0,
    val infeasible: Boolean = false,
    val lpHarvest: LpHarvestReport? = null,
    /** Wall time the deferred base bake (presolve step 0, [com.eignex.klause.solver.Problem.bakeBase])
     *  took; zero when the bake ran at construction instead. */
    val bakeElapsed: Duration = Duration.ZERO,
)
