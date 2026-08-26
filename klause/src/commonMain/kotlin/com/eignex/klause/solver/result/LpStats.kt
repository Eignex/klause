package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.CountStat
import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.MaxStat
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

/**
 * LP-relaxation bounding counters, split out of [SolveStats] so the whole LP diagnostic surface —
 * definition, merge, accumulation, and snapshot — lives in one place. Produced entirely by
 * `backtrack/lp`; zero for backends that never solve a relaxation.
 */
data class LpStats(
    /** Node LP-bounding passes that built and solved a relaxation — the denominator for the prune /
     *  fix / pivot rates. [pruned] alone is meaningless without knowing how many solves it took. */
    val solves: SumResult = ZERO_COUNT,
    /** Nodes pruned by the LP-relaxation bound: infeasible relaxation or bound ≥ incumbent.
     *  Split into [infeasible] (relaxation infeasible) and the remainder (bound dominated). */
    val pruned: SumResult = ZERO_COUNT,
    /** Subset of [pruned] where the relaxation itself was infeasible (a feasibility filter, not a
     *  bound); `pruned − infeasible` is the bound-dominated count. */
    val infeasible: SumResult = ZERO_COUNT,
    /** Root-node LP relaxation objective (the live dual bound at decision level 0), or NaN when the
     *  LP never solved at the root. Against the final objective this is the integrality gap. */
    val rootBound: Double = Double.NaN,
    /** Wall time (ms) spent inside LP bounding — the cost side of the LP ROI (benefit = prunes/fixes). */
    val ms: Long = 0L,
    /** Domain reductions applied by LP reduced-cost fixing. */
    val fixed: SumResult = ZERO_COUNT,
    /** Total dual-simplex pivots across all node LP solves; drops sharply with warm-starting. */
    val pivots: SumResult = ZERO_COUNT,
    /** Max sparse-LU fill ratio `(nnz L+U)/nnz B` over all factorizations; >1 = fill-in growth. */
    val luMaxFill: MaxResult = NO_MAX,
    /** Max sparse-LU density `(nnz L+U)/m²`; approaching 1.0 means the LU filled in to effectively dense. */
    val luMaxDensity: MaxResult = NO_MAX,
    /** LP cuts added by separators. */
    val cuts: SumResult = ZERO_COUNT,
    /** Non-chronological backjumps driven by an LP infeasibility (Farkas) certificate. */
    val backjumps: SumResult = ZERO_COUNT,
    /** Node LP solves that started from a prior basis instead of the slack cold start — the warm-start
     *  hit rate. A warm basis saves pivots but not the factorization; [refactorizations] is that cost. */
    val seeded: SumResult = ZERO_COUNT,
    /** Sparse LU factorizations built across all node LP solves. While each node constructs its own
     *  engine the floor is one per solve, so this measures what carrying one across nodes would save. */
    val refactorizations: SumResult = ZERO_COUNT,
    /** Certified LP solves whose model decomposed into column components (`lp-component-split`); the
     *  denominator for judging the split is [solves] on the paths that carry a sink. */
    val componentSplits: SumResult = ZERO_COUNT,
    /** Largest component count any one decomposed solve produced; 0 when none decomposed. */
    val componentBlocks: MaxResult = NO_MAX,
) {
    /** Combine two workers' LP stats: counts add, LU maxes take the larger, wall time sums, and the
     *  root bound (same root across workers) keeps the tightest finite reading (NaN defers). */
    fun mergedWith(o: LpStats): LpStats = LpStats(
        solves = SumResult(solves.sum + o.solves.sum),
        pruned = SumResult(pruned.sum + o.pruned.sum),
        infeasible = SumResult(infeasible.sum + o.infeasible.sum),
        rootBound = naNDeferring(rootBound, o.rootBound, ::maxOf),
        ms = ms + o.ms,
        fixed = SumResult(fixed.sum + o.fixed.sum),
        pivots = SumResult(pivots.sum + o.pivots.sum),
        luMaxFill = MaxResult(maxOf(luMaxFill.max, o.luMaxFill.max)),
        luMaxDensity = MaxResult(maxOf(luMaxDensity.max, o.luMaxDensity.max)),
        cuts = SumResult(cuts.sum + o.cuts.sum),
        backjumps = SumResult(backjumps.sum + o.backjumps.sum),
        seeded = SumResult(seeded.sum + o.seeded.sum),
        refactorizations = SumResult(refactorizations.sum + o.refactorizations.sum),
        componentSplits = SumResult(componentSplits.sum + o.componentSplits.sum),
        componentBlocks = MaxResult(maxOf(componentBlocks.max, o.componentBlocks.max)),
    )
}

/** Mutable LP-stats accumulator, one per solve; snapshots into an [LpStats]. See [SolveStatsSink]. */
internal class LpStatsSink {
    val solves: CountStat = CountStat()
    val pruned: CountStat = CountStat()
    val infeasible: CountStat = CountStat()
    val fixed: CountStat = CountStat()
    val pivots: CountStat = CountStat()
    val luMaxFill: MaxStat = MaxStat()
    val luMaxDensity: MaxStat = MaxStat()
    val cuts: CountStat = CountStat()
    val backjumps: CountStat = CountStat()
    val seeded: CountStat = CountStat()
    val refactorizations: CountStat = CountStat()
    val componentSplits: CountStat = CountStat()
    val componentBlocks: MaxStat = MaxStat()

    private var rootBound: Double = Double.NaN
    private var ms: Long = 0L
    private var clock: TimeMark? = null

    /** One node LP-bounding pass that built and solved a relaxation (the rate denominator). */
    fun observeSolve() {
        solves.update(1.0)
    }

    /** A node whose subtree was cut by the LP-relaxation bound because its bound dominated the
     *  incumbent (or an LP-derived deduction emptied a domain). */
    fun observePrune() {
        pruned.update(1.0)
    }

    /** A node pruned because the LP relaxation was infeasible — counted in both [pruned] (the total)
     *  and [infeasible] (the feasibility-filter share). */
    fun observeInfeasiblePrune() {
        pruned.update(1.0)
        infeasible.update(1.0)
    }

    /** Record the root-node (decision level 0) LP relaxation objective; last write at the root wins,
     *  so it reflects the strengthened post-cut bound. Ignored off the root or for a non-finite value. */
    fun observeRootBound(decisionLevel: Int, value: Double) {
        if (decisionLevel == 0 && value.isFinite()) rootBound = value
    }

    /** Bracket LP-bounding wall time: [clockStart] then [clockStop] adds the interval to [ms]. */
    fun clockStart() {
        clock = Monotonic.markNow()
    }
    fun clockStop() {
        val mark = clock ?: return
        ms += mark.elapsedNow().inWholeMilliseconds
        clock = null
    }

    /** One domain reduction applied by LP reduced-cost fixing. */
    fun observeFix() {
        fixed.update(1.0)
    }

    /** Record [count] dual-simplex pivots from one node LP solve. */
    fun observePivots(count: Int) {
        repeat(count) { pivots.update(1.0) }
    }

    /** Record one node LP solve's sparse-LU fill ratio and density. */
    fun observeLuFill(fill: Double, density: Double) {
        if (fill > 0.0) luMaxFill.update(fill)
        if (density > 0.0) luMaxDensity.update(density)
    }

    /** Record [count] cuts added by separators. */
    fun observeCuts(count: Int) {
        repeat(count) { cuts.update(1.0) }
    }

    /** A non-chronological backjump driven by an LP infeasibility certificate. */
    fun observeBackjump() {
        backjumps.update(1.0)
    }

    /** A node LP solve that started from a prior basis instead of the slack cold start. */
    fun observeSeeded() {
        seeded.update(1.0)
    }

    /** Record how one node LP solve started: [warmStarted] off a prior basis, and the [refactorizations]
     *  it built getting there and back to optimal. */
    fun observeStart(warmStarted: Boolean, refactorizations: Int) {
        if (warmStarted) seeded.update(1.0)
        repeat(refactorizations) { this.refactorizations.update(1.0) }
    }

    /** One certified LP solve that decomposed into [blocks] column components. A monolithic solve
     *  (`blocks == 1`) is not recorded, so [componentSplits] counts only the split ones. */
    fun observeComponentSplit(blocks: Int) {
        if (blocks <= 1) return
        componentSplits.update(1.0)
        componentBlocks.update(blocks.toDouble())
    }

    fun snapshot(): LpStats = LpStats(
        solves = solves.read(),
        pruned = pruned.read(),
        infeasible = infeasible.read(),
        rootBound = rootBound,
        ms = ms,
        fixed = fixed.read(),
        pivots = pivots.read(),
        luMaxFill = luMaxFill.read(),
        luMaxDensity = luMaxDensity.read(),
        cuts = cuts.read(),
        backjumps = backjumps.read(),
        seeded = seeded.read(),
        refactorizations = refactorizations.read(),
        componentSplits = componentSplits.read(),
        componentBlocks = componentBlocks.read(),
    )
}
