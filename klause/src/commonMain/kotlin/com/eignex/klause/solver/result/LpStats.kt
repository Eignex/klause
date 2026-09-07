package com.eignex.klause.solver.result

import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.kumulant.stat.summary.CountStat
import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.MaxStat
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

/** Attempts of one exact certifier. A decline is an attempted check without a certificate; it never
 * says the float claim or the model is false. */
data class LpCertifierStats(
    val attempts: SumResult = ZERO_COUNT,
    val successes: SumResult = ZERO_COUNT,
    val declines: SumResult = ZERO_COUNT,
) {
    fun mergedWith(other: LpCertifierStats) = LpCertifierStats(
        SumResult(attempts.sum + other.attempts.sum),
        SumResult(successes.sum + other.successes.sum),
        SumResult(declines.sum + other.declines.sum),
    )
}

/** Consumer route for a single engine invocation. */
internal enum class LpRoute { NODE, STANDALONE, COMPONENT, ROOT }

/**
 * LP-relaxation bounding counters, split out of [SolveStats] so the whole LP diagnostic surface —
 * definition, merge, accumulation, and snapshot — lives in one place. Produced entirely by
 * `backtrack/lp`; zero for backends that never solve a relaxation.
 */
data class LpStats(
    val nodePasses: SumResult = ZERO_COUNT,
    val standalonePasses: SumResult = ZERO_COUNT,
    val componentPasses: SumResult = ZERO_COUNT,
    val rootPasses: SumResult = ZERO_COUNT,
    val standalonePivots: SumResult = ZERO_COUNT,
    val standaloneWorkOps: SumResult = ZERO_COUNT,
    val componentPivots: SumResult = ZERO_COUNT,
    val componentWorkOps: SumResult = ZERO_COUNT,
    val rootPivots: SumResult = ZERO_COUNT,
    val rootWorkOps: SumResult = ZERO_COUNT,
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
    /**
     * Total deterministic LP work (floating-point operations) across all node LP solves.
     *
     * Unlike [pivots] this reflects what each pivot cost: a dense basis, a long eta chain and a
     * refactorization all charge more than a cheap sparse step. Reproducible run to run, so a budget
     * keyed on it behaves the same on a loaded machine as on an idle one.
     */
    val workOps: SumResult = ZERO_COUNT,
    /** Max sparse-LU fill ratio `(nnz L+U)/nnz B` over all factorizations; >1 = fill-in growth. */
    val luMaxFill: MaxResult = NO_MAX,
    /** Max sparse-LU density `(nnz L+U)/m²`; approaching 1.0 means the LU filled in to effectively dense. */
    val luMaxDensity: MaxResult = NO_MAX,
    /** LP cuts added by separators. */
    val cuts: SumResult = ZERO_COUNT,
    val cutCandidates: SumResult = ZERO_COUNT,
    val cutSelected: SumResult = ZERO_COUNT,
    val cutActive: SumResult = ZERO_COUNT,
    val rootReducedCostFixes: SumResult = ZERO_COUNT,
    /** Non-chronological backjumps driven by an LP infeasibility (Farkas) certificate. */
    val backjumps: SumResult = ZERO_COUNT,
    /** Node LP solves that started from a prior basis instead of the slack cold start — the warm-start
     *  hit rate. A warm basis saves pivots but not the factorization; [refactorizations] is that cost. */
    val seeded: SumResult = ZERO_COUNT,
    /** Sparse LU factorizations built across all node LP solves. While each node constructs its own
     *  engine the floor is one per solve, so this measures what carrying one across nodes would save. */
    val refactorizations: SumResult = ZERO_COUNT,
    val warmStartAttempts: SumResult = ZERO_COUNT,
    val warmStartHits: SumResult = ZERO_COUNT,
    val initialRefactorizations: SumResult = ZERO_COUNT,
    val warmStartRefactorizations: SumResult = ZERO_COUNT,
    val singularRecoveryRefactorizations: SumResult = ZERO_COUNT,
    val updateLimitRefactorizations: SumResult = ZERO_COUNT,
    val backendRequestedRefactorizations: SumResult = ZERO_COUNT,
    val reconcileRecoveryRefactorizations: SumResult = ZERO_COUNT,
    val primalRefactorizations: SumResult = ZERO_COUNT,
    /** Whether the wall-clock backstop demoted the node LP — the one policy input that is not
     *  deterministic, so a run whose counters do not reproduce is explained by this being set. */
    val wallBackstop: Boolean = false,
    /** Whether the node LP was ever demoted to its floor budget, by either rule.
     *
     *  Distinct from [wallBackstop], which says only that the clock rather than the work meter decided.
     *  Without this a demotion by the deterministic rule leaves no trace at all, so a run that spent its
     *  budget on a throttled relaxation reads the same as one that never throttled. */
    val demoted: Boolean = false,
    /** Certified LP solves whose model decomposed into column components (`lp-component-split`); the
     *  denominator for judging the split is [solves] on the paths that carry a sink. */
    val componentSplits: SumResult = ZERO_COUNT,
    /** Largest component count any one decomposed solve produced; 0 when none decomposed. */
    val componentBlocks: MaxResult = NO_MAX,
    /** Certified solves whose exact integer certificate was produced. */
    val certified: SumResult = ZERO_COUNT,
    /** Declines because the model carries a real coefficient, which the integer certifier refuses by
     *  design. Expected, and not a shortfall in the exact layer's reach. */
    val certifyDeclinedContinuous: SumResult = ZERO_COUNT,
    /** Declines for an arithmetic reason on an integer model — overflow, an out-of-range multiplier, a
     *  negative reduced cost on an unbounded column. Each is a certificate the exact layer could in
     *  principle have had, so this is the number that sizes its reach. */
    val certifyDeclinedNumeric: SumResult = ZERO_COUNT,
    /** Solves that reached the rational decider — the slow lane every decline falls into. */
    val rationalFallbacks: SumResult = ZERO_COUNT,
    /** Largest row count seen at a certified solve, against which `MAX_EXACT_BASIS` (48) is applied. */
    val certifyMaxRows: MaxResult = NO_MAX,
    /** Farkas certificates produced by rational reconstruction of the float ray. */
    val farkasReconstructed: SumResult = ZERO_COUNT,
    /** Farkas certificates produced by the exact basis solve (the `MAX_EXACT_BASIS`-capped route). */
    val farkasExactBasis: SumResult = ZERO_COUNT,
    /** Farkas certificates produced by rounding the float ray. */
    val farkasRounded: SumResult = ZERO_COUNT,
    /** Dual-unbounded terminations no route could certify. */
    val farkasNone: SumResult = ZERO_COUNT,
    val integerCertify: LpCertifierStats = LpCertifierStats(),
    val safeObjectiveLowerBound: LpCertifierStats = LpCertifierStats(),
    val exactBasisFeasible: LpCertifierStats = LpCertifierStats(),
    val exactFarkasRay: LpCertifierStats = LpCertifierStats(),
    val exactPointFeasible: LpCertifierStats = LpCertifierStats(),
    val rationalOutcome: LpCertifierStats = LpCertifierStats(),
    val exactInputAttempts: SumResult = ZERO_COUNT,
    val exactInputRejections: SumResult = ZERO_COUNT,
    /**
     * Basis factorizations across all node LP solves that came back singular.
     *
     * Each one cost a warm start: the engine falls back to the slack basis and refactorizes again, so
     * the [seeded] hit rate above overstates how many solves actually kept their warm basis. Against
     * [refactorizations] this is the rate at which the relaxation's columns defeat the factorization.
     */
    val singularRefactorizations: SumResult = ZERO_COUNT,
    /** Node LP solves abandoned mid-pivot because the pivot element was numerically too small. The
     *  engine does not refactorize and retry, so each is a solve lost outright. */
    val smallPivotBails: SumResult = ZERO_COUNT,
    /** Smallest structural `|a_ij|` in the root relaxation, or NaN when never measured. With
     *  [rootMatrixMaxValue] this is what a scaling decision is made on. */
    val rootMatrixMinValue: Double = Double.NaN,
    /** Largest structural `|a_ij|` in the root relaxation, or NaN when never measured. */
    val rootMatrixMaxValue: Double = Double.NaN,
    /** Worst within-row `max/min` magnitude in the root relaxation, or NaN when never measured. The
     *  part of the spread that row scaling could absorb, as against a uniform rescale. */
    val rootRowRatio: Double = Double.NaN,
) {
    /** Combine two workers' LP stats: counts add, LU maxes take the larger, wall time sums, and the
     *  root bound (same root across workers) keeps the tightest finite reading (NaN defers). */
    fun mergedWith(o: LpStats): LpStats = LpStats(
        nodePasses = SumResult(nodePasses.sum + o.nodePasses.sum),
        standalonePasses = SumResult(standalonePasses.sum + o.standalonePasses.sum),
        componentPasses = SumResult(componentPasses.sum + o.componentPasses.sum),
        rootPasses = SumResult(rootPasses.sum + o.rootPasses.sum),
        standalonePivots = SumResult(standalonePivots.sum + o.standalonePivots.sum),
        standaloneWorkOps = SumResult(standaloneWorkOps.sum + o.standaloneWorkOps.sum),
        componentPivots = SumResult(componentPivots.sum + o.componentPivots.sum),
        componentWorkOps = SumResult(componentWorkOps.sum + o.componentWorkOps.sum),
        rootPivots = SumResult(rootPivots.sum + o.rootPivots.sum),
        rootWorkOps = SumResult(rootWorkOps.sum + o.rootWorkOps.sum),
        solves = SumResult(solves.sum + o.solves.sum),
        pruned = SumResult(pruned.sum + o.pruned.sum),
        infeasible = SumResult(infeasible.sum + o.infeasible.sum),
        rootBound = naNDeferring(rootBound, o.rootBound, ::maxOf),
        ms = ms + o.ms,
        fixed = SumResult(fixed.sum + o.fixed.sum),
        pivots = SumResult(pivots.sum + o.pivots.sum),
        workOps = SumResult(workOps.sum + o.workOps.sum),
        luMaxFill = MaxResult(maxOf(luMaxFill.max, o.luMaxFill.max)),
        luMaxDensity = MaxResult(maxOf(luMaxDensity.max, o.luMaxDensity.max)),
        cuts = SumResult(cuts.sum + o.cuts.sum),
        cutCandidates = SumResult(cutCandidates.sum + o.cutCandidates.sum),
        cutSelected = SumResult(cutSelected.sum + o.cutSelected.sum),
        cutActive = SumResult(cutActive.sum + o.cutActive.sum),
        rootReducedCostFixes = SumResult(rootReducedCostFixes.sum + o.rootReducedCostFixes.sum),
        backjumps = SumResult(backjumps.sum + o.backjumps.sum),
        seeded = SumResult(seeded.sum + o.seeded.sum),
        refactorizations = SumResult(refactorizations.sum + o.refactorizations.sum),
        warmStartAttempts = SumResult(warmStartAttempts.sum + o.warmStartAttempts.sum),
        warmStartHits = SumResult(warmStartHits.sum + o.warmStartHits.sum),
        initialRefactorizations = SumResult(initialRefactorizations.sum + o.initialRefactorizations.sum),
        warmStartRefactorizations = SumResult(warmStartRefactorizations.sum + o.warmStartRefactorizations.sum),
        singularRecoveryRefactorizations = SumResult(
            singularRecoveryRefactorizations.sum + o.singularRecoveryRefactorizations.sum,
        ),
        updateLimitRefactorizations = SumResult(updateLimitRefactorizations.sum + o.updateLimitRefactorizations.sum),
        backendRequestedRefactorizations = SumResult(
            backendRequestedRefactorizations.sum + o.backendRequestedRefactorizations.sum,
        ),
        reconcileRecoveryRefactorizations = SumResult(
            reconcileRecoveryRefactorizations.sum + o.reconcileRecoveryRefactorizations.sum,
        ),
        primalRefactorizations = SumResult(primalRefactorizations.sum + o.primalRefactorizations.sum),
        wallBackstop = wallBackstop || o.wallBackstop,
        demoted = demoted || o.demoted,
        componentSplits = SumResult(componentSplits.sum + o.componentSplits.sum),
        componentBlocks = MaxResult(maxOf(componentBlocks.max, o.componentBlocks.max)),
        singularRefactorizations = SumResult(singularRefactorizations.sum + o.singularRefactorizations.sum),
        smallPivotBails = SumResult(smallPivotBails.sum + o.smallPivotBails.sum),
        rootMatrixMinValue = naNDeferring(rootMatrixMinValue, o.rootMatrixMinValue, ::minOf),
        rootMatrixMaxValue = naNDeferring(rootMatrixMaxValue, o.rootMatrixMaxValue, ::maxOf),
        rootRowRatio = naNDeferring(rootRowRatio, o.rootRowRatio, ::maxOf),
        certified = SumResult(certified.sum + o.certified.sum),
        certifyDeclinedContinuous = SumResult(certifyDeclinedContinuous.sum + o.certifyDeclinedContinuous.sum),
        certifyDeclinedNumeric = SumResult(certifyDeclinedNumeric.sum + o.certifyDeclinedNumeric.sum),
        rationalFallbacks = SumResult(rationalFallbacks.sum + o.rationalFallbacks.sum),
        certifyMaxRows = MaxResult(maxOf(certifyMaxRows.max, o.certifyMaxRows.max)),
        farkasReconstructed = SumResult(farkasReconstructed.sum + o.farkasReconstructed.sum),
        farkasExactBasis = SumResult(farkasExactBasis.sum + o.farkasExactBasis.sum),
        farkasRounded = SumResult(farkasRounded.sum + o.farkasRounded.sum),
        farkasNone = SumResult(farkasNone.sum + o.farkasNone.sum),
        integerCertify = integerCertify.mergedWith(o.integerCertify),
        safeObjectiveLowerBound = safeObjectiveLowerBound.mergedWith(o.safeObjectiveLowerBound),
        exactBasisFeasible = exactBasisFeasible.mergedWith(o.exactBasisFeasible),
        exactFarkasRay = exactFarkasRay.mergedWith(o.exactFarkasRay),
        exactPointFeasible = exactPointFeasible.mergedWith(o.exactPointFeasible),
        rationalOutcome = rationalOutcome.mergedWith(o.rationalOutcome),
        exactInputAttempts = SumResult(exactInputAttempts.sum + o.exactInputAttempts.sum),
        exactInputRejections = SumResult(exactInputRejections.sum + o.exactInputRejections.sum),
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
    val certified: CountStat = CountStat()
    val certifyDeclinedContinuous: CountStat = CountStat()
    val certifyDeclinedNumeric: CountStat = CountStat()
    val rationalFallbacks: CountStat = CountStat()
    val certifyMaxRows: MaxStat = MaxStat()
    val farkasReconstructed: CountStat = CountStat()
    val farkasExactBasis: CountStat = CountStat()
    val farkasRounded: CountStat = CountStat()
    val farkasNone: CountStat = CountStat()
    val singularRefactorizations: CountStat = CountStat()
    val smallPivotBails: CountStat = CountStat()

    private var nodePasses = 0L
    private var standalonePasses = 0L
    private var componentPasses = 0L
    private var rootPasses = 0L
    private var standalonePivots = 0L
    private var standaloneWorkOps = 0L
    private var componentPivots = 0L
    private var componentWorkOps = 0L
    private var rootPivots = 0L
    private var rootWorkOps = 0L
    private var warmStartAttempts = 0L
    private var warmStartHits = 0L
    private var initialRefactorizations = 0L
    private var warmStartRefactorizations = 0L
    private var singularRecoveryRefactorizations = 0L
    private var updateLimitRefactorizations = 0L
    private var backendRequestedRefactorizations = 0L
    private var reconcileRecoveryRefactorizations = 0L
    private var primalRefactorizations = 0L
    private var cutCandidates = 0L
    private var cutSelected = 0L
    private var cutActive = 0L
    private var rootReducedCostFixes = 0L
    private val certifierAttempts = LongArray(LpCertifier.entries.size)
    private val certifierSuccesses = LongArray(LpCertifier.entries.size)
    private val certifierDeclines = LongArray(LpCertifier.entries.size)
    private var exactInputAttempts = 0L
    private var exactInputRejections = 0L

    private var rootBound: Double = Double.NaN
    private var rootMatrixMinValue: Double = Double.NaN
    private var rootMatrixMaxValue: Double = Double.NaN
    private var rootRowRatio: Double = Double.NaN
    private var ms: Long = 0L

    // A running total, not a [CountStat]: that counts observations and ignores their magnitude, which is
    // how the other counters record units (a unit update repeated). Work is a magnitude per solve.
    private var workOpsTotal: Long = 0L
    private var wallBackstop = false
    private var demoted = false
    private var clock: TimeMark? = null

    /** One node LP-bounding pass that built and solved a relaxation (the rate denominator). */
    fun observeSolve() {
        solves.update(1.0)
        nodePasses++
    }

    /** Route-specific engine cost. Node callers retain the legacy aggregate while standalone, component,
     * and root auxiliary callers get their own denominators. */
    fun observeEngineCost(route: LpRoute, metrics: LpSolveMetrics) {
        when (route) {
            LpRoute.NODE -> Unit

            LpRoute.STANDALONE -> {
                standalonePasses++
                standalonePivots += metrics.pivots
                standaloneWorkOps +=
                    metrics.workOps
            }

            LpRoute.COMPONENT -> {
                componentPasses++
                componentPivots += metrics.pivots
                componentWorkOps +=
                    metrics.workOps
            }

            LpRoute.ROOT -> {
                rootPasses++
                rootPivots += metrics.pivots
                rootWorkOps += metrics.workOps
            }
        }
        observeMetrics(metrics)
    }

    /** The local bridge passed into engine exact checks. */
    fun certificationObserver(): LpCertificationObserver = object : LpCertificationObserver {
        override fun observe(certifier: LpCertifier, success: Boolean) {
            val i = certifier.ordinal
            certifierAttempts[i]++
            if (success) certifierSuccesses[i]++ else certifierDeclines[i]++
        }
        override fun observeExactInput(accepted: Boolean) {
            exactInputAttempts++
            if (!accepted) exactInputRejections++
        }
        override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) {
            observeEngineCost(if (component) LpRoute.COMPONENT else LpRoute.STANDALONE, metrics)
        }
    }

    fun observeCutAccounting(candidates: Int, selected: Int, active: Int) {
        cutCandidates += candidates.coerceAtLeast(0)
        cutSelected += selected.coerceAtLeast(0)
        cutActive += active.coerceAtLeast(0)
    }

    fun observeRootReducedCostFixes(count: Int) {
        rootReducedCostFixes += count.coerceAtLeast(0)
    }

    private fun observeMetrics(metrics: LpSolveMetrics) {
        observePivots(metrics.pivots)
        observeWork(metrics.workOps)
        repeat(metrics.warmHits) { seeded.update(1.0) }
        warmStartAttempts += metrics.warmAttempts
        warmStartHits += metrics.warmHits
        repeat(
            metrics.initialRefactorizations + metrics.warmStartRefactorizations +
                metrics.singularRecoveryRefactorizations +
                metrics.updateLimitRefactorizations + metrics.backendRequestedRefactorizations +
                metrics.reconcileRecoveryRefactorizations + metrics.primalRefactorizations,
        ) { refactorizations.update(1.0) }
        initialRefactorizations += metrics.initialRefactorizations
        warmStartRefactorizations += metrics.warmStartRefactorizations
        singularRecoveryRefactorizations += metrics.singularRecoveryRefactorizations
        updateLimitRefactorizations += metrics.updateLimitRefactorizations
        backendRequestedRefactorizations += metrics.backendRequestedRefactorizations
        reconcileRecoveryRefactorizations += metrics.reconcileRecoveryRefactorizations
        primalRefactorizations += metrics.primalRefactorizations
        observeNumericalTrouble(metrics.singularRefactorizations, metrics.smallPivotBails)
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

    /** Record that the wall-clock backstop, not the deterministic work rule, demoted the LP. */
    fun observeWallBackstop() {
        wallBackstop = true
    }

    /** Record that the node LP stands demoted to its floor budget, whichever rule decided. */
    fun observeDemoted() {
        demoted = true
    }

    /** Record one node LP solve's deterministic work. Accumulated in a single update: the figure runs
     *  to millions of operations, so a per-unit loop would cost more than the solve it measures. */
    fun observeWork(ops: Long) {
        if (ops > 0L) workOpsTotal += ops
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

    /**
     * Record the numerical trouble one node LP solve met: [singular] factorizations and [smallPivot]
     * pivots abandoned as too small.
     *
     * Read off the engine rather than a [com.eignex.klause.lp.engine.FloatLpResult], on the same
     * grounds as [observePivots]: a solve lost to either has no result, and those are precisely the
     * solves worth counting here.
     */
    fun observeNumericalTrouble(singular: Int, smallPivot: Int) {
        repeat(singular) { singularRefactorizations.update(1.0) }
        repeat(smallPivot) { smallPivotBails.update(1.0) }
    }

    /** Record the root-node (decision level 0) relaxation's coefficient spread; first write at the root
     *  wins, since the matrix is fixed for a model's lifetime. Ignored off the root or for an empty
     *  matrix. */
    fun observeRootConditioning(decisionLevel: Int, minValue: Double, maxValue: Double, rowRatio: Double) {
        if (decisionLevel != 0 || !rootMatrixMinValue.isNaN() || maxValue <= 0.0) return
        rootMatrixMinValue = minValue
        rootMatrixMaxValue = maxValue
        rootRowRatio = rowRatio
    }

    /** Record how one node LP solve started: [warmStarted] off a prior basis, and the [refactorizations]
     *  it built getting there and back to optimal. */
    fun observeStart(warmStarted: Boolean, refactorizations: Int) {
        if (warmStarted) seeded.update(1.0)
        repeat(refactorizations) { this.refactorizations.update(1.0) }
    }

    /**
     * Record how one certified solve was decided, and where it declined if it did.
     *
     * The decline rate sizes the exact layer's reach — a decline is at once a lost prune, a lost clause
     * and a fall-through to the rational decider — so the two causes are counted apart. A continuous
     * decline is the certifier refusing a real coefficient by design; only a numeric one is a
     * certificate that was arithmetically out of reach.
     */
    fun observeCertification(
        certified: Boolean,
        continuousDecline: Boolean,
        numericDecline: Boolean,
        rationalFallback: Boolean,
        rows: Int,
    ) {
        if (certified) this.certified.update(1.0)
        if (continuousDecline) certifyDeclinedContinuous.update(1.0)
        if (numericDecline) certifyDeclinedNumeric.update(1.0)
        if (rationalFallback) rationalFallbacks.update(1.0)
        if (rows > 0) certifyMaxRows.update(rows.toDouble())
    }

    /** Which route produced one Farkas certificate, or that none did. */
    fun observeFarkasRoute(reconstructed: Boolean, exactBasis: Boolean, rounded: Boolean, none: Boolean) {
        if (reconstructed) farkasReconstructed.update(1.0)
        if (exactBasis) farkasExactBasis.update(1.0)
        if (rounded) farkasRounded.update(1.0)
        if (none) farkasNone.update(1.0)
    }

    /** One certified LP solve that decomposed into [blocks] column components. A monolithic solve
     *  (`blocks == 1`) is not recorded, so [componentSplits] counts only the split ones. */
    fun observeComponentSplit(blocks: Int) {
        if (blocks <= 1) return
        componentSplits.update(1.0)
        componentBlocks.update(blocks.toDouble())
    }

    fun snapshot(): LpStats = LpStats(
        nodePasses = SumResult(nodePasses.toDouble()),
        standalonePasses = SumResult(standalonePasses.toDouble()),
        componentPasses = SumResult(componentPasses.toDouble()),
        rootPasses = SumResult(rootPasses.toDouble()),
        standalonePivots = SumResult(standalonePivots.toDouble()),
        standaloneWorkOps = SumResult(standaloneWorkOps.toDouble()),
        componentPivots = SumResult(componentPivots.toDouble()),
        componentWorkOps = SumResult(componentWorkOps.toDouble()),
        rootPivots = SumResult(rootPivots.toDouble()),
        rootWorkOps = SumResult(rootWorkOps.toDouble()),
        solves = solves.read(),
        pruned = pruned.read(),
        infeasible = infeasible.read(),
        rootBound = rootBound,
        ms = ms,
        fixed = fixed.read(),
        pivots = pivots.read(),
        workOps = SumResult(workOpsTotal.toDouble()),
        wallBackstop = wallBackstop,
        demoted = demoted,
        luMaxFill = luMaxFill.read(),
        luMaxDensity = luMaxDensity.read(),
        cuts = cuts.read(),
        cutCandidates = SumResult(cutCandidates.toDouble()),
        cutSelected = SumResult(cutSelected.toDouble()),
        cutActive = SumResult(cutActive.toDouble()),
        rootReducedCostFixes = SumResult(rootReducedCostFixes.toDouble()),
        backjumps = backjumps.read(),
        seeded = seeded.read(),
        refactorizations = refactorizations.read(),
        warmStartAttempts = SumResult(warmStartAttempts.toDouble()),
        warmStartHits = SumResult(warmStartHits.toDouble()),
        initialRefactorizations = SumResult(initialRefactorizations.toDouble()),
        warmStartRefactorizations = SumResult(warmStartRefactorizations.toDouble()),
        singularRecoveryRefactorizations = SumResult(singularRecoveryRefactorizations.toDouble()),
        updateLimitRefactorizations = SumResult(updateLimitRefactorizations.toDouble()),
        backendRequestedRefactorizations = SumResult(backendRequestedRefactorizations.toDouble()),
        reconcileRecoveryRefactorizations = SumResult(reconcileRecoveryRefactorizations.toDouble()),
        primalRefactorizations = SumResult(primalRefactorizations.toDouble()),
        componentSplits = componentSplits.read(),
        componentBlocks = componentBlocks.read(),
        singularRefactorizations = singularRefactorizations.read(),
        smallPivotBails = smallPivotBails.read(),
        rootMatrixMinValue = rootMatrixMinValue,
        rootMatrixMaxValue = rootMatrixMaxValue,
        rootRowRatio = rootRowRatio,
        certified = certified.read(),
        certifyDeclinedContinuous = certifyDeclinedContinuous.read(),
        certifyDeclinedNumeric = certifyDeclinedNumeric.read(),
        rationalFallbacks = rationalFallbacks.read(),
        certifyMaxRows = certifyMaxRows.read(),
        farkasReconstructed = farkasReconstructed.read(),
        farkasExactBasis = farkasExactBasis.read(),
        farkasRounded = farkasRounded.read(),
        farkasNone = farkasNone.read(),
        integerCertify = certifierStats(LpCertifier.INTEGER),
        safeObjectiveLowerBound = certifierStats(LpCertifier.SAFE_OBJECTIVE),
        exactBasisFeasible = certifierStats(LpCertifier.EXACT_BASIS),
        exactFarkasRay = certifierStats(LpCertifier.EXACT_FARKAS),
        exactPointFeasible = certifierStats(LpCertifier.EXACT_POINT),
        rationalOutcome = certifierStats(LpCertifier.RATIONAL),
        exactInputAttempts = SumResult(exactInputAttempts.toDouble()),
        exactInputRejections = SumResult(exactInputRejections.toDouble()),
    )

    private fun certifierStats(certifier: LpCertifier): LpCertifierStats {
        val i = certifier.ordinal
        return LpCertifierStats(
            SumResult(certifierAttempts[i].toDouble()),
            SumResult(certifierSuccesses[i].toDouble()),
            SumResult(certifierDeclines[i].toDouble()),
        )
    }
}
