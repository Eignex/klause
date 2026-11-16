package com.eignex.klause.solver.result

import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.SumResult

/** Attempts of one exact certifier. A decline is an attempted check without a certificate; it never
 * says the float claim or the model is false. */
data class LpCertifierStats(
    /** Attempts across every production route. */
    val attempts: SumResult = ZERO_COUNT,
    /** Successful attempts across every production route. */
    val successes: SumResult = ZERO_COUNT,
    /** Attempts that produced no certificate across every production route. */
    val declines: SumResult = ZERO_COUNT,
    /** Attempts from search-node bounding. */
    val node: LpCertifierRouteStats = LpCertifierRouteStats(),
    /** Attempts from standalone LP consumers. */
    val standalone: LpCertifierRouteStats = LpCertifierRouteStats(),
    /** Attempts from decomposed component solves. */
    val component: LpCertifierRouteStats = LpCertifierRouteStats(),
    /** Attempts from root and presolve LP consumers. */
    val root: LpCertifierRouteStats = LpCertifierRouteStats(),
) {
    /** Combine independent route observations. */
    fun mergedWith(other: LpCertifierStats) = LpCertifierStats(
        SumResult(attempts.sum + other.attempts.sum),
        SumResult(successes.sum + other.successes.sum),
        SumResult(declines.sum + other.declines.sum),
        node.mergedWith(other.node),
        standalone.mergedWith(other.standalone),
        component.mergedWith(other.component),
        root.mergedWith(other.root),
    )
}

/** Attempts of one exact certifier attributed to a single production route. */
data class LpCertifierRouteStats(
    /** Number of times the certifier ran on this route. */
    val attempts: SumResult = ZERO_COUNT,
    /** Number of attempts that yielded a certificate on this route. */
    val successes: SumResult = ZERO_COUNT,
    /** Number of attempts that yielded no certificate on this route. */
    val declines: SumResult = ZERO_COUNT,
) {
    /** Combine independent observations of this route. */
    fun mergedWith(other: LpCertifierRouteStats) = LpCertifierRouteStats(
        SumResult(attempts.sum + other.attempts.sum),
        SumResult(successes.sum + other.successes.sum),
        SumResult(declines.sum + other.declines.sum),
    )
}

/** Complete modeled work and declines of rational basis verification, including abandoned attempts. */
data class LpBasisVerificationStats(
    /** Offered verification calls. */
    val calls: Long = 0L,
    /** Calls with valid, bounded source authority and basis declarations. */
    val eligible: Long = 0L,
    /** Factor factory invocations. */
    val factoryCalls: Long = 0L,
    /** Factor builds, including precision restarts and abandoned attempts. */
    val builds: Long = 0L,
    /** Calls reusing completed factors. */
    val reuse: Long = 0L,
    /** Triangular solve invocations, including failed solves. */
    val solves: Long = 0L,
    /** Arithmetic restarts across factors, solves and source verification. */
    val restarts: Long = 0L,
    /** Independent source point, dual and ray checks. */
    val checks: Long = 0L,
    /** Modeled work by operation phase. */
    val work: Map<String, Long> = emptyMap(),
    /** Cumulative modeled allocation reservations by operation phase. */
    val allocation: Map<String, Long> = emptyMap(),
    /** Terminal reasons, including resource exits retaining a complete independent proof. */
    val declines: Map<String, Long> = emptyMap(),
    /** Terminal counts keyed by operation phase and reason, separated by an underscore. */
    val terminalDeclines: Map<String, Long> = emptyMap(),
    /** Offered calls by consumer route. */
    val routes: Map<String, Long> = emptyMap(),
) {
    /** Combine independent complete observations without dropping failed work. */
    fun mergedWith(other: LpBasisVerificationStats) = LpBasisVerificationStats(
        calls + other.calls, eligible + other.eligible, factoryCalls + other.factoryCalls, builds + other.builds,
        reuse + other.reuse, solves + other.solves, restarts + other.restarts, checks + other.checks,
        mergeBasisCounts(work, other.work), mergeBasisCounts(allocation, other.allocation),
        mergeBasisCounts(declines, other.declines), mergeBasisCounts(terminalDeclines, other.terminalDeclines),
        mergeBasisCounts(routes, other.routes),
    )
}

private fun mergeBasisCounts(first: Map<String, Long>, second: Map<String, Long>): Map<String, Long> =
    (first.keys + second.keys).associateWith { (first[it] ?: 0L) + (second[it] ?: 0L) }

/** Exact continuation costs, including abandoned imports and arithmetic restarts. */
data class LpContinuationStats(
    /** Offered recovery invocations. */
    val calls: Long = 0,
    /** Invocations with validated neutral input and target declarations. */
    val eligible: Long = 0,
    /** Invocations producing a complete numerical source proof before policy. */
    val successes: Long = 0,
    /** Imported tableau builds, including abandoned builds. */
    val builds: Long = 0,
    /** Completed import pivots, including replay after overflow. */
    val imports: Long = 0,
    /** Completed feasibility pivots, including abandoned lanes. */
    val pivots: Long = 0,
    /** Dependent target columns replaced by logicals. */
    val repairs: Long = 0,
    /** Whole fixed-width operation restarts. */
    val restarts: Long = 0,
    /** Invocations resuming a compatible state. */
    val resumes: Long = 0,
    /** Invocations invalidating incompatible retained state. */
    val invalidations: Long = 0,
    /** Complete source verifier invocations. */
    val checks: Long = 0,
    /** Modeled work per phase. */
    val work: Map<String, Long> = emptyMap(),
    /** Modeled cumulative allocation reservations per phase. */
    val allocation: Map<String, Long> = emptyMap(),
    /** Terminal declines keyed by phase and reason. */
    val declines: Map<String, Long> = emptyMap(),
    /** Active invocation time, excluding time between resumes. */
    val elapsedNs: Long = 0,
) {
    /** Add disjoint invocation deltas. */
    fun mergedWith(other: LpContinuationStats) = LpContinuationStats(
        calls + other.calls, eligible + other.eligible, successes + other.successes, builds + other.builds,
        imports + other.imports, pivots + other.pivots, repairs + other.repairs, restarts + other.restarts,
        resumes + other.resumes, invalidations + other.invalidations, checks + other.checks,
        mergeBasisCounts(work, other.work), mergeBasisCounts(allocation, other.allocation),
        mergeBasisCounts(declines, other.declines), elapsedNs + other.elapsedNs,
    )
}

internal fun ExactContinuationMetrics.toStats() = LpContinuationStats(
    calls = 1, eligible = if (eligible) 1 else 0, successes = if (success) 1 else 0,
    builds = builds.toLong(), imports = imports.toLong(), pivots = pivots.toLong(), repairs = repairs.toLong(),
    restarts = restarts.toLong(), resumes = if (resumed) 1 else 0, invalidations = if (invalidated) 1 else 0,
    checks = checks.toLong(), work = workByPhase.mapKeys { it.key.name },
    allocation = allocationByPhase.mapKeys { it.key.name },
    declines = decline?.let { mapOf("${phase.name}_${it.name}" to 1L) } ?: emptyMap(), elapsedNs = elapsedNs,
)

/** Consumer route for a single engine invocation. */
internal enum class LpRoute { NODE, STANDALONE, COMPONENT, ROOT }

/** Complete engine telemetry for one non-node production route. */
data class LpRouteSolveStats(
    /** Engine invocations on the route, including failed solves. */
    val passes: SumResult = ZERO_COUNT,
    /** Simplex pivots on the route. */
    val pivots: SumResult = ZERO_COUNT,
    /** Deterministic floating-point work on the route. */
    val workOps: SumResult = ZERO_COUNT,
    /** Invocations that requested reuse of a prior basis. */
    val warmStartAttempts: SumResult = ZERO_COUNT,
    /** Reuse attempts that retained the prior basis. */
    val warmStartHits: SumResult = ZERO_COUNT,
    /** Refactorizations from a cold slack basis. */
    val initialRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations after accepting a warm basis. */
    val warmStartRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations recovering a singular basis or update. */
    val singularRecoveryRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations after the eta-update chain limit. */
    val updateLimitRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations requested by the basis backend. */
    val backendRequestedRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations restoring unenforced gated rows. */
    val reconcileRecoveryRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations retrying a near-tolerance infeasibility candidate. */
    val numericalRecoveryRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations during primal simplex. */
    val primalRefactorizations: SumResult = ZERO_COUNT,
    /** Factorizations that came back singular. */
    val singularRefactorizations: SumResult = ZERO_COUNT,
    /** Solves abandoned because a pivot was numerically too small. */
    val smallPivotBails: SumResult = ZERO_COUNT,
) {
    /** Combine independent observations of this route. */
    fun mergedWith(other: LpRouteSolveStats) = LpRouteSolveStats(
        SumResult(passes.sum + other.passes.sum),
        SumResult(pivots.sum + other.pivots.sum),
        SumResult(workOps.sum + other.workOps.sum),
        SumResult(warmStartAttempts.sum + other.warmStartAttempts.sum),
        SumResult(warmStartHits.sum + other.warmStartHits.sum),
        SumResult(initialRefactorizations.sum + other.initialRefactorizations.sum),
        SumResult(warmStartRefactorizations.sum + other.warmStartRefactorizations.sum),
        SumResult(singularRecoveryRefactorizations.sum + other.singularRecoveryRefactorizations.sum),
        SumResult(updateLimitRefactorizations.sum + other.updateLimitRefactorizations.sum),
        SumResult(backendRequestedRefactorizations.sum + other.backendRequestedRefactorizations.sum),
        SumResult(reconcileRecoveryRefactorizations.sum + other.reconcileRecoveryRefactorizations.sum),
        SumResult(numericalRecoveryRefactorizations.sum + other.numericalRecoveryRefactorizations.sum),
        SumResult(primalRefactorizations.sum + other.primalRefactorizations.sum),
        SumResult(singularRefactorizations.sum + other.singularRefactorizations.sum),
        SumResult(smallPivotBails.sum + other.smallPivotBails.sum),
    )
}

/**
 * LP-relaxation bounding counters, including merge, accumulation and snapshot support.
 * Zero for backends that never solve a relaxation.
 */
data class LpStats(
    /** Search nodes that entered LP bounding. */
    val nodePasses: SumResult = ZERO_COUNT,
    /** Standalone LP engine invocations. */
    val standalonePasses: SumResult = ZERO_COUNT,
    /** Decomposed component LP engine invocations. */
    val componentPasses: SumResult = ZERO_COUNT,
    /** Root or presolve LP engine invocations. */
    val rootPasses: SumResult = ZERO_COUNT,
    /** Pivots spent by standalone LP invocations. */
    val standalonePivots: SumResult = ZERO_COUNT,
    /** Deterministic work spent by standalone LP invocations. */
    val standaloneWorkOps: SumResult = ZERO_COUNT,
    /** Pivots spent by component LP invocations. */
    val componentPivots: SumResult = ZERO_COUNT,
    /** Deterministic work spent by component LP invocations. */
    val componentWorkOps: SumResult = ZERO_COUNT,
    /** Pivots spent by root or presolve LP invocations. */
    val rootPivots: SumResult = ZERO_COUNT,
    /** Deterministic work spent by root or presolve LP invocations. */
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
    /** Total simplex pivots across all node LP solves. */
    val pivots: SumResult = ZERO_COUNT,
    /**
     * Total deterministic LP work (floating-point operations) across all node LP solves.
     *
     * Unlike [pivots] this reflects what each pivot cost: a dense basis, a long eta chain and a
     * refactorization all charge more than a cheap sparse step. Reproducible run to run, so a budget
     * keyed on it behaves the same on a loaded machine as on an idle one.
     */
    val workOps: SumResult = ZERO_COUNT,
    /**
     * Deterministic node-LP work outside the simplex, on the [workOps] scale: relaxation builds, solver setup,
     * exact certification, reduced-cost fixing and scoped bound edits. [workOps] plus this is what a node LP costs.
     */
    val overheadOps: SumResult = ZERO_COUNT,
    /** Max sparse-LU fill ratio `(nnz L+U)/nnz B` over all factorizations; >1 = fill-in growth. */
    val luMaxFill: MaxResult = NO_MAX,
    /** Max sparse-LU density `(nnz L+U)/m²`; approaching 1.0 means the LU filled in to effectively dense. */
    val luMaxDensity: MaxResult = NO_MAX,
    /** Largest basis dimension `m` seen across all node LP solves, independent of [luMaxDensity]. */
    val luMaxDim: MaxResult = NO_MAX,
    /** LP cuts added by separators. */
    val cuts: SumResult = ZERO_COUNT,
    /** Fresh cuts generated by separators. */
    val cutCandidates: SumResult = ZERO_COUNT,
    /** Cuts selected into a relaxation build. */
    val cutSelected: SumResult = ZERO_COUNT,
    /** Largest number of cuts simultaneously active in a relaxation. */
    val cutActive: MaxResult = NO_MAX,
    /** Root domain reductions applied by reduced-cost fixing. */
    val rootReducedCostFixes: SumResult = ZERO_COUNT,
    /** Non-chronological backjumps driven by an LP infeasibility (Farkas) certificate. */
    val backjumps: SumResult = ZERO_COUNT,
    /** Node LP solves that started from a prior basis instead of the slack cold start — the warm-start
     *  hit rate. A warm basis saves pivots but not the factorization; [refactorizations] is that cost. */
    val seeded: SumResult = ZERO_COUNT,
    /** Sparse LU factorizations built across all node LP solves, for any reason. */
    val refactorizations: SumResult = ZERO_COUNT,
    /** Persistent solves that considered a warm basis. */
    val warmStartAttempts: SumResult = ZERO_COUNT,
    /** Persistent solves that retained a warm basis. */
    val warmStartHits: SumResult = ZERO_COUNT,
    /** Refactorizations from a cold slack basis. */
    val initialRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations needed after accepting a warm basis. */
    val warmStartRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations recovering a singular basis or update. */
    val singularRecoveryRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations after the eta-update chain limit. */
    val updateLimitRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations requested by the basis backend. */
    val backendRequestedRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations restoring unenforced gated rows. */
    val reconcileRecoveryRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations retrying a near-tolerance infeasibility candidate. */
    val numericalRecoveryRefactorizations: SumResult = ZERO_COUNT,
    /** Refactorizations during primal simplex. */
    val primalRefactorizations: SumResult = ZERO_COUNT,
    /** Whether root and node LP work spent the LP work allowance (`LpPlan.lpWorkBudgetFraction`),
     *  after which optional node LP work stops. */
    val workAllowanceSpent: Boolean = false,
    /** Whether the node LP was ever demoted to its floor budget, by either rule.
     *
     *  Distinct from [workAllowanceSpent], which says only that the cumulative allowance rather than the
     *  per-node ratio decided.
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
    /** Largest row count seen at a certified solve. */
    val certifyMaxRows: MaxResult = NO_MAX,
    /** Farkas certificates produced by rational reconstruction of the float ray. */
    val farkasReconstructed: SumResult = ZERO_COUNT,
    /** Farkas certificates produced by rational basis verification. */
    val farkasExactBasis: SumResult = ZERO_COUNT,
    /** Farkas certificates produced by rounding the float ray. */
    val farkasRounded: SumResult = ZERO_COUNT,
    /** Dual-unbounded terminations no route could certify. */
    val farkasNone: SumResult = ZERO_COUNT,
    /** Per-route attempts of the integer dual certifier. */
    val integerCertify: LpCertifierStats = LpCertifierStats(),
    /** Per-route attempts of the safe float objective certifier. */
    val safeObjectiveLowerBound: LpCertifierStats = LpCertifierStats(),
    /** Per-route attempts of the exact basis feasibility certifier. */
    val exactBasisFeasible: LpCertifierStats = LpCertifierStats(),
    /** Complete rational basis verification work and resource exits. */
    val basisVerification: LpBasisVerificationStats = LpBasisVerificationStats(),
    /** Exact continuation invocation totals. */
    val continuation: LpContinuationStats = LpContinuationStats(),
    /** Per-route attempts of the exact Farkas certifier. */
    val exactFarkasRay: LpCertifierStats = LpCertifierStats(),
    /** Per-route attempts of the exact primal-point certifier. */
    val exactPointFeasible: LpCertifierStats = LpCertifierStats(),
    /** Per-route attempts of the rational fallback decider. */
    val rationalOutcome: LpCertifierStats = LpCertifierStats(),
    /** Continuous-model exact-input rationalization attempts. */
    val exactInputAttempts: SumResult = ZERO_COUNT,
    /** Exact-input rationalizations rejected before certification. */
    val exactInputRejections: SumResult = ZERO_COUNT,
    /**
     * Basis factorizations across all node LP solves that came back singular.
     *
     * Each one goes to basis repair, and a warm basis that stays singular falls back to the slack start, so
     * the [seeded] hit rate above overstates how many solves actually kept their warm basis. Against
     * [refactorizations] this is the rate at which the relaxation's columns defeat the factorization.
     */
    val singularRefactorizations: SumResult = ZERO_COUNT,
    /** Node LP pivots abandoned because the pivot element was numerically too small. A scaled solve
     *  retries once unscaled; otherwise the bail loses the solve. */
    val smallPivotBails: SumResult = ZERO_COUNT,
    /** Smallest structural `|a_ij|` in the root relaxation, or NaN when never measured. With
     *  [rootMatrixMaxValue] this is what a scaling decision is made on. */
    val rootMatrixMinValue: Double = Double.NaN,
    /** Largest structural `|a_ij|` in the root relaxation, or NaN when never measured. */
    val rootMatrixMaxValue: Double = Double.NaN,
    /** Worst within-row `max/min` magnitude in the root relaxation, or NaN when never measured. The
     *  part of the spread that row scaling could absorb, as against a uniform rescale. */
    val rootRowRatio: Double = Double.NaN,
    /** Complete standalone-engine telemetry. */
    val standaloneRoute: LpRouteSolveStats = LpRouteSolveStats(),
    /** Complete decomposed-component engine telemetry. */
    val componentRoute: LpRouteSolveStats = LpRouteSolveStats(),
    /** Complete root and presolve engine telemetry. */
    val rootRoute: LpRouteSolveStats = LpRouteSolveStats(),
    /** Inclusive phase timings and outcomes, keyed by engine phase and consumer route. */
    val phases: Map<String, LpPhaseStats> = emptyMap(),
    /** Peak sparse-trial admission estimates and decline indicators across workers. */
    val sparseTrial: Map<String, Long> = emptyMap(),
) {
    /** Combine two workers' LP stats: counts add, LU maxes take the larger, wall time sums, and the
     *  root bound (same root across workers) keeps the tightest finite reading (NaN defers). */
    fun mergedWith(o: LpStats): LpStats = LpStats(
        phases = mergePhaseStats(phases, o.phases),
        sparseTrial = (sparseTrial.keys + o.sparseTrial.keys).associateWith {
            maxOf(sparseTrial[it] ?: 0L, o.sparseTrial[it] ?: 0L)
        },
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
        standaloneRoute = standaloneRoute.mergedWith(o.standaloneRoute),
        componentRoute = componentRoute.mergedWith(o.componentRoute),
        rootRoute = rootRoute.mergedWith(o.rootRoute),
        solves = SumResult(solves.sum + o.solves.sum),
        pruned = SumResult(pruned.sum + o.pruned.sum),
        infeasible = SumResult(infeasible.sum + o.infeasible.sum),
        rootBound = naNDeferring(rootBound, o.rootBound, ::maxOf),
        ms = ms + o.ms,
        fixed = SumResult(fixed.sum + o.fixed.sum),
        pivots = SumResult(pivots.sum + o.pivots.sum),
        workOps = SumResult(workOps.sum + o.workOps.sum),
        overheadOps = SumResult(overheadOps.sum + o.overheadOps.sum),
        luMaxFill = MaxResult(maxOf(luMaxFill.max, o.luMaxFill.max)),
        luMaxDensity = MaxResult(maxOf(luMaxDensity.max, o.luMaxDensity.max)),
        luMaxDim = MaxResult(maxOf(luMaxDim.max, o.luMaxDim.max)),
        cuts = SumResult(cuts.sum + o.cuts.sum),
        cutCandidates = SumResult(cutCandidates.sum + o.cutCandidates.sum),
        cutSelected = SumResult(cutSelected.sum + o.cutSelected.sum),
        cutActive = MaxResult(maxOf(cutActive.max, o.cutActive.max)),
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
        numericalRecoveryRefactorizations = SumResult(
            numericalRecoveryRefactorizations.sum + o.numericalRecoveryRefactorizations.sum,
        ),
        primalRefactorizations = SumResult(primalRefactorizations.sum + o.primalRefactorizations.sum),
        workAllowanceSpent = workAllowanceSpent || o.workAllowanceSpent,
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
        basisVerification = basisVerification.mergedWith(o.basisVerification),
        continuation = continuation.mergedWith(o.continuation),
        exactFarkasRay = exactFarkasRay.mergedWith(o.exactFarkasRay),
        exactPointFeasible = exactPointFeasible.mergedWith(o.exactPointFeasible),
        rationalOutcome = rationalOutcome.mergedWith(o.rationalOutcome),
        exactInputAttempts = SumResult(exactInputAttempts.sum + o.exactInputAttempts.sum),
        exactInputRejections = SumResult(exactInputRejections.sum + o.exactInputRejections.sum),
    )
}

/** Whether an LP engine was invoked on any production route. */
internal fun LpStats.hasActivity(): Boolean =
    nodePasses.sum + standalonePasses.sum + componentPasses.sum + rootPasses.sum > 0.0
