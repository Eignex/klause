package com.eignex.klause.solver.result

import com.eignex.klause.lp.engine.ExactBasisMetrics
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpPhaseMetrics
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.kumulant.stat.summary.CountStat
import com.eignex.kumulant.stat.summary.MaxStat
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

/** Mutable LP-stats accumulator, one per solve; snapshots into an [LpStats]. See [SolveStatsSink]. */
internal class LpStatsSink(private val probeRoute: LpRoute = LpRoute.NODE) {
    var sparseTrial: Map<String, Long> = emptyMap()
    private val phases = LinkedHashMap<String, LpPhaseStats>()
    private val directionalOutcomes = LinkedHashMap<String, Long>()
    private var directionalCalls = 0L
    private var templatePreparationSeen = false
    private var templateAssemblySeen = false
    private var templateNanos = 0L
    private var templateWork = 0L
    private var templatePrepared = 0L
    private var templateDeclined = 0L
    private var templateFactorsReused = 0L
    private var templateSourceRebound = 0L
    private var templateIndexesReused = 0L
    private var templateBoundsReused = 0L
    private var templateComponentsReused = 0L
    private var templateComponentsDirty = 0L
    private var templateRetainedUnits = 0L
    private var templateRows = 0L
    private var templateBounds = 0L
    private var templateExactBounds = 0L
    private var templateBoundChecks = 0L
    private var templateBoundColumnWork = 0L
    private var templateRowsOmitted = 0L
    private var templateBoundRowsSeen = false
    val cutLifecycle = LpCutStats { key, delta ->
        phases[key] = (phases[key] ?: LpPhaseStats()).mergedWith(delta)
    }

    fun observeTemplateFunding(nanos: Long, work: Long, completedUnits: Long, recorded: Boolean) {
        val key = "TEMPLATE_FUNDING_${probeRoute.name}"
        val outcome = if (recorded) "RECORDED" else "RETAINED"
        val delta = LpPhaseStats(calls = 1L, nanos = nanos, work = work,
            outcomes = mapOf(outcome to 1L, "COMPLETED_UNITS_SUM" to completedUnits))
        phases[key] = (phases[key] ?: LpPhaseStats()).mergedWith(delta)
    }

    fun observeTemplatePreparation(
        nanos: Long,
        work: Long,
        prepared: Boolean,
        factorsReused: Int = 0,
        sourceRebound: Boolean = false,
        indexesReused: Boolean = false,
        boundsReused: Int = 0,
        componentsReused: Int = 0,
        componentsDirty: Int = 0,
        retainedUnits: Long = 0L,
    ) {
        if (!templatePreparationSeen) {
            templatePreparationSeen = true
            phases.getOrPut("TEMPLATE_PREPARATION_${probeRoute.name}") { LpPhaseStats() }
        }
        templateNanos += nanos
        templateWork += work
        if (!prepared) {
            templateDeclined++
            return
        }
        templatePrepared++
        templateFactorsReused += factorsReused
        if (sourceRebound) templateSourceRebound++
        if (indexesReused) templateIndexesReused++
        templateBoundsReused += boundsReused
        templateComponentsReused += componentsReused
        templateComponentsDirty += componentsDirty
        templateRetainedUnits += retainedUnits
    }

    fun observeTemplateUse(
        rows: Int = 0, bounds: Int = 0, exactBounds: Int = 0, boundChecks: Int = 0, rowsOmitted: Int = 0,
    ) {
        if (!templateAssemblySeen) {
            templateAssemblySeen = true
            phases.getOrPut("TEMPLATE_ASSEMBLY_${probeRoute.name}") { LpPhaseStats() }
        }
        templateRows += rows
        templateBounds += bounds
        templateExactBounds += exactBounds
        if (boundChecks > 0 || rowsOmitted > 0) templateBoundRowsSeen = true
        templateBoundChecks += boundChecks
        templateRowsOmitted += rowsOmitted
    }

    fun observeTemplateBoundStorageWork(work: Long) {
        observeTemplateUse()
        templateBoundRowsSeen = true
        templateBoundColumnWork += work
    }

    private fun phaseSnapshot(): Map<String, LpPhaseStats> {
        if (!templatePreparationSeen && !templateAssemblySeen && directionalCalls == 0L) return phases.toMap()
        return phases.toMutableMap().apply {
            if (directionalCalls != 0L) {
                val key = "DIRECTIONAL_BRANCH_NODE"
                put(key, getValue(key).mergedWith(LpPhaseStats(
                    calls = directionalCalls, outcomes = directionalOutcomes.toMap(),
                )))
            }
            if (templatePreparationSeen) {
                val outcomes = buildMap {
                    if (templateDeclined != 0L) put("DECLINED", templateDeclined)
                    if (templatePrepared != 0L) {
                        put("PREPARED", templatePrepared)
                        put("FACTORS_REUSED", templateFactorsReused)
                        put("SOURCE_REBOUND", templateSourceRebound)
                        put("INDEXES_REUSED", templateIndexesReused)
                        put("BOUNDS_REUSED", templateBoundsReused)
                        put("COMPONENTS_REUSED", templateComponentsReused)
                        put("COMPONENTS_DIRTY", templateComponentsDirty)
                        put("RETAINED_UNITS_SUM", templateRetainedUnits)
                    }
                }
                val key = "TEMPLATE_PREPARATION_${probeRoute.name}"
                put(key, getValue(key).mergedWith(LpPhaseStats(
                    calls = templatePrepared + templateDeclined,
                    nanos = templateNanos, work = templateWork, outcomes = outcomes,
                )))
            }
            if (templateAssemblySeen) {
                val key = "TEMPLATE_ASSEMBLY_${probeRoute.name}"
                put(key, getValue(key).mergedWith(LpPhaseStats(
                    work = templateBoundChecks + templateBoundColumnWork,
                    outcomes = buildMap {
                        put("ROWS", templateRows)
                        put("BOUNDS", templateBounds)
                        put("EXACT_BOUND_OBJECTS", templateExactBounds)
                        if (templateBoundRowsSeen) {
                            put("BOUND_ROW_CHECKS", templateBoundChecks)
                            put("BOUND_COLUMN_WORK", templateBoundColumnWork)
                            put("SOURCE_ROWS_OMITTED", templateRowsOmitted)
                        }
                    },
                )))
            }
        }
    }

    fun directionalBranchCount(outcome: String): Long = directionalOutcomes[outcome] ?: 0L

    fun observeDirectionalBranch(outcome: String) {
        if (directionalCalls == 0L) phases.getOrPut("DIRECTIONAL_BRANCH_NODE") { LpPhaseStats() }
        directionalCalls++
        directionalOutcomes[outcome] = (directionalOutcomes[outcome] ?: 0L) + 1L
    }

    fun observePrimalPhase(stage: String, outcome: String, nanos: Long, work: Long = 0L) {
        val key = "${stage}_ROOT"
        val delta = LpPhaseStats(calls = 1L, nanos = nanos, work = work, outcomes = mapOf(outcome to 1L))
        phases[key] = (phases[key] ?: LpPhaseStats()).mergedWith(delta)
    }

    fun observeComponentAdmission(outcome: String, blocks: Int, modeledBytes: Long) {
        val key = "COMPONENT_ADMISSION_${probeRoute.name}"
        val delta = LpPhaseStats(calls = 1L, outcomes = mapOf(
            outcome to 1L, "BLOCKS_SUM" to blocks.toLong(), "MODELED_BYTES_SUM" to modeledBytes,
        ))
        phases[key] = (phases[key] ?: LpPhaseStats()).mergedWith(delta)
    }

    val solves: CountStat = CountStat()
    val pruned: CountStat = CountStat()
    val infeasible: CountStat = CountStat()
    val fixed: CountStat = CountStat()
    val luMaxFill: MaxStat = MaxStat()
    val luMaxDensity: MaxStat = MaxStat()
    val luMaxDim: MaxStat = MaxStat()
    val cuts: CountStat = CountStat()
    val backjumps: CountStat = CountStat()
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

    private var nodePasses = 0L

    // Standalone component work also spends its caller's allowance.
    private val standaloneComponents = LpRouteSolveStatsSink()
    internal val standaloneWork: Long
        get() = routeMetrics[LpRoute.STANDALONE.ordinal].work + standaloneComponents.work
    private var cutCandidates = 0L
    private var cutSelected = 0L
    private val cutActive = MaxStat()
    private var rootReducedCostFixes = 0L
    private val certifiers = Array(LpCertifier.entries.size) { LpCertifierStatsSink() }
    private var exactInputAttempts = 0L
    private var exactInputRejections = 0L
    private val routeMetrics = Array(LpRoute.entries.size) { LpRouteSolveStatsSink(node = it == LpRoute.NODE.ordinal) }

    private var rootBound: Double = Double.NaN
    private var rootMatrixMinValue: Double = Double.NaN
    private var rootMatrixMaxValue: Double = Double.NaN
    private var rootRowRatio: Double = Double.NaN
    private var ms: Long = 0L

    private var overheadOpsTotal: Long = 0L
    private var workAllowanceSpent = false
    private var demoted = false
    private var clock: TimeMark? = null

    /** One node visited by LP bounding, irrespective of how many re-solves cuts require. */
    fun observeNodePass() {
        if (probeRoute == LpRoute.NODE) nodePasses++
    }

    /** One node LP solve, the cost denominator; [observeNodePass] is the node count. */
    fun observeSolve() {
        if (probeRoute == LpRoute.NODE) solves.update(1.0)
    }

    /** Route-specific engine cost. The pivot, work, warm-start and refactor totals are node-only,
     * so their denominator ([solves]) always names the same route. */
    fun observeEngineCost(route: LpRoute, metrics: LpSolveMetrics, finished: Boolean = true) {
        recordEngineCost(route, metrics, finished)
    }

    private fun recordEngineCost(
        route: LpRoute, metrics: LpSolveMetrics, finished: Boolean, standaloneComponent: Boolean = false,
    ) {
        val effectiveRoute = if (route == LpRoute.NODE) probeRoute else route
        cutLifecycle.shared("SOLVE", LpPhaseStats(calls = if (finished) 1L else 0L,
            work = metrics.workOps + metrics.preparationOps, pivots = metrics.pivots.toLong(),
            outcomes = mapOf("COMPONENT_SOLVED" to metrics.componentSolves.toLong(),
                "COMPONENT_REUSED" to metrics.componentReuses.toLong(),
                "COLD_FACTOR" to metrics.initialRefactorizations.toLong(),
                "WARM_FACTOR" to metrics.warmStartRefactorizations.toLong(),
                "SINGULAR_FACTOR" to metrics.singularRecoveryRefactorizations.toLong())))
        val basisBuild = LpPhaseStats(work = metrics.basisBuildWork,
            outcomes = mapOf("DECLINED" to metrics.basisBuildDeclines,
                "UNMEASURED" to metrics.basisBuildUnmeasured, "FACTOR_ENTRIES" to metrics.factorEntriesBuilt))
        val basisKey = "BASIS_BUILD_${effectiveRoute.name}"
        phases[basisKey] = (phases[basisKey] ?: LpPhaseStats()).mergedWith(basisBuild)
        cutLifecycle.shared("BASIS_BUILD", basisBuild)
        if (metrics.componentSolves > 0 || metrics.componentReuses > 0) {
            val key = "COMPONENT_LIFECYCLE_${effectiveRoute.name}"
            phases[key] = (phases[key] ?: LpPhaseStats()).mergedWith(
                LpPhaseStats(calls = metrics.componentSolves.toLong() + metrics.componentReuses,
                    outcomes = mapOf("SOLVED" to metrics.componentSolves.toLong(),
                        "REUSED" to metrics.componentReuses.toLong())),
            )
        }
        if (metrics.sourceResidualCalls > 0L) {
            val key = "SOURCE_RESIDUALS_${effectiveRoute.name}"
            phases[key] = (phases[key] ?: LpPhaseStats()).mergedWith(
                LpPhaseStats(calls = metrics.sourceResidualCalls, nanos = metrics.sourceResidualNanos,
                    outcomes = mapOf("SCALED" to metrics.sourceResidualScaledCalls,
                        "UNSCALED" to metrics.sourceResidualCalls - metrics.sourceResidualScaledCalls)),
            )
        }
        val owner = if (standaloneComponent) standaloneComponents else routeMetrics[effectiveRoute.ordinal]
        owner.observe(metrics, finished)
    }

    private var basisVerification = LpBasisVerificationStats()
    private var continuation = LpContinuationStats()

    private val certificationObservers: Array<LpCertificationObserver> by lazy {
        Array(LpRoute.entries.size) { index ->
            val route = LpRoute.entries[index]
            object : LpCertificationObserver {
                override fun observePhase(metrics: LpPhaseMetrics) {
                    val key = "${metrics.phase.name}_${route.name}"
                    val delta = LpPhaseStats(
                        1L,
                        metrics.nanos,
                        metrics.work,
                        metrics.pivots.toLong(),
                        metrics.steps.toLong(),
                        mapOf(metrics.outcome to 1L),
                    )
                    phases[key] = (phases[key] ?: LpPhaseStats()).mergedWith(delta)
                    cutLifecycle.shared(metrics.phase.name, delta)
                    if (metrics.phase == LpSolvePhase.NODE_DEDUCTION) {
                        val detail = "${key}_${metrics.outcome}"
                        phases[detail] = (phases[detail] ?: LpPhaseStats()).mergedWith(delta)
                    }
                }
                override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                    cutLifecycle.shared("CERTIFIER_${certifier.name}", LpPhaseStats(calls = 1L,
                        work = (cost as? LpCertifierCost.Metered)?.work ?: 0L,
                        outcomes = mapOf((if (success) "CERTIFIED" else "DECLINED") to 1L)))
                    certifiers[certifier.ordinal].observe(route, success)
                }
                override fun observeContinuation(metrics: ExactContinuationMetrics) {
                    continuation = continuation.mergedWith(metrics.toStats())
                    for ((phase, work) in metrics.workByPhase) {
                        cutLifecycle.shared("CONTINUATION_${phase.name}", LpPhaseStats(calls = 1L, work = work,
                            outcomes = mapOf((metrics.decline?.name ?: "COMPLETED") to 1L,
                                "ALLOCATION" to (metrics.allocationByPhase[phase] ?: 0L))))
                    }
                }
                override fun observeBasisVerification(metrics: ExactBasisMetrics) {
                    for (operation in metrics.operations) {
                        cutLifecycle.shared("BASIS_${operation.phase.name}", LpPhaseStats(calls = 1L,
                            work = operation.work, outcomes = mapOf(
                                (metrics.decline?.name ?: "COMPLETED") to 1L,
                                "ALLOCATION" to operation.allocation)))
                    }
                    basisVerification = basisVerification.mergedWith(
                        LpBasisVerificationStats(
                            calls = 1L, eligible = if (metrics.eligible) 1L else 0L,
                            factoryCalls = metrics.factoryCalls.toLong(), builds = metrics.builds.toLong(),
                            reuse = metrics.reuse.toLong(), solves = metrics.solves.toLong(),
                            restarts = metrics.restarts.toLong(), checks = metrics.verificationChecks.toLong(),
                            work = metrics.operations.associate { it.phase.name to it.work },
                            allocation = metrics.operations.associate { it.phase.name to it.allocation },
                            declines = metrics.decline?.let { mapOf(it.name to 1L) } ?: emptyMap(),
                            terminalDeclines = metrics.decline?.let {
                                mapOf("${metrics.phase.name}_${it.name}" to 1L)
                            } ?: emptyMap(),
                            routes = mapOf(route.name to 1L),
                        ),
                    )
                }
                override fun observeExactInput(accepted: Boolean) {
                    exactInputAttempts++
                    if (!accepted) exactInputRejections++
                }
                override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) {
                    observeCertifiedSolve(route, metrics, component, true)
                }

                override fun observeSolveProgress(metrics: LpSolveMetrics, component: Boolean, finished: Boolean) {
                    observeCertifiedSolve(route, metrics, component, finished)
                }
            }
        }
    }

    private fun observeCertifiedSolve(route: LpRoute, metrics: LpSolveMetrics, component: Boolean, finished: Boolean) {
        recordEngineCost(if (component) LpRoute.COMPONENT else route, metrics, finished,
            standaloneComponent = component && route == LpRoute.STANDALONE)
    }

    /** The local bridge passed into engine exact checks. */
    fun certificationObserver(route: LpRoute = LpRoute.NODE): LpCertificationObserver {
        val effectiveRoute = if (route == LpRoute.NODE) probeRoute else route
        return certificationObservers[effectiveRoute.ordinal]
    }

    fun observeCutAccounting(candidates: Int, selected: Int, active: Int) {
        cutCandidates += candidates.coerceAtLeast(0)
        cutSelected += selected.coerceAtLeast(0)
        if (active > 0) cutActive.update(active.toDouble())
    }

    /** Count a selection before its build and make it active only after that build succeeds. */
    fun <T> observeCutBuild(selected: Int, build: () -> T): T {
        observeCutAccounting(candidates = 0, selected = selected, active = 0)
        val result = build()
        if (result != null) observeCutAccounting(candidates = 0, selected = 0, active = selected)
        return result
    }

    fun observeRootReducedCostFixes(count: Int) {
        if (probeRoute == LpRoute.NODE) rootReducedCostFixes += count.coerceAtLeast(0)
    }

    /** A node whose subtree was cut by the LP-relaxation bound because its bound dominated the
     *  incumbent (or an LP-derived deduction emptied a domain). */
    fun observePrune() {
        if (probeRoute == LpRoute.NODE) pruned.update(1.0)
    }

    /** A node pruned because the LP relaxation was infeasible — counted in both [pruned] (the total)
     *  and [infeasible] (the feasibility-filter share). */
    fun observeInfeasiblePrune() {
        if (probeRoute != LpRoute.NODE) return
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
        if (probeRoute == LpRoute.NODE) clock = Monotonic.markNow()
    }
    fun clockStop() {
        val mark = clock ?: return
        ms += mark.elapsedNow().inWholeMilliseconds
        clock = null
    }

    /** One domain reduction applied by LP reduced-cost fixing. */
    fun observeFix() {
        if (probeRoute == LpRoute.NODE) fixed.update(1.0)
    }

    /** Record that LP work spent its cumulative allowance. */
    fun observeWorkAllowanceSpent() {
        workAllowanceSpent = true
    }

    /** Record that the node LP stands demoted to its floor budget, whichever rule decided. */
    fun observeDemoted() {
        demoted = true
    }

    /** Record one node LP solve's deterministic work. Accumulated in a single update: the figure runs
     *  to millions of operations, so a per-unit loop would cost more than the solve it measures. */
    fun observeWork(ops: Long) {
        routeMetrics[LpRoute.NODE.ordinal].observeWork(ops)
    }

    /** Record node-LP work the simplex does not meter; see [LpStats.overheadOps]. */
    fun observeOverhead(ops: Long) {
        if (ops > 0L && probeRoute == LpRoute.NODE) overheadOpsTotal += ops
    }

    /** Record one node LP solve's sparse-LU fill ratio, density, and basis dimension. [luMaxDim] is
     *  the largest basis seen across all solves, tracked independently of [luMaxDensity] — a single
     *  running max cannot also report the dimension at which the max density occurred, so treat the
     *  two as separate signals (how sparse things get, and how big things get) rather than a pair. */
    fun observeLuFill(fill: Double, density: Double, dimension: Int = 0) {
        if (fill > 0.0) luMaxFill.update(fill)
        if (density > 0.0) luMaxDensity.update(density)
        if (dimension > 0) luMaxDim.update(dimension.toDouble())
    }

    /** Record [count] cuts added by separators. */
    fun observeCuts(count: Int) {
        repeat(count) { cuts.update(1.0) }
    }

    /** A non-chronological backjump driven by an LP infeasibility certificate. */
    fun observeBackjump() {
        backjumps.update(1.0)
    }

    /** Record the root-node (decision level 0) relaxation's coefficient spread; first write at the root
     *  wins, since the matrix is fixed for a model's lifetime. Ignored off the root or for an empty
     *  matrix. */
    fun observeRootConditioning(decisionLevel: Int, minValue: Double, maxValue: Double, rowRatio: Double) {
        if (probeRoute != LpRoute.NODE || decisionLevel != 0 || !rootMatrixMinValue.isNaN() || maxValue <= 0.0) return
        rootMatrixMinValue = minValue
        rootMatrixMaxValue = maxValue
        rootRowRatio = rowRatio
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

    fun snapshot(): LpStats {
        val node = routeMetrics[LpRoute.NODE.ordinal].snapshot()
        val standalone = routeMetrics[LpRoute.STANDALONE.ordinal].snapshot()
        val component = routeMetrics[LpRoute.COMPONENT.ordinal].snapshot(standaloneComponents)
        val root = routeMetrics[LpRoute.ROOT.ordinal].snapshot()
        return snapshot(node, standalone, component, root)
    }

    private fun snapshot(
        node: LpRouteSolveStats, standalone: LpRouteSolveStats, component: LpRouteSolveStats, root: LpRouteSolveStats,
    ): LpStats = LpStats(
        phases = phaseSnapshot(),
        sparseTrial = sparseTrial,
        nodePasses = SumResult(nodePasses.toDouble()),
        standalonePasses = standalone.passes,
        componentPasses = component.passes,
        rootPasses = root.passes,
        standalonePivots = standalone.pivots,
        standaloneWorkOps = standalone.workOps,
        componentPivots = component.pivots,
        componentWorkOps = component.workOps,
        rootPivots = root.pivots,
        rootWorkOps = root.workOps,
        standaloneRoute = standalone,
        componentRoute = component,
        rootRoute = root,
        solves = solves.read(),
        pruned = pruned.read(),
        infeasible = infeasible.read(),
        rootBound = rootBound,
        ms = ms,
        fixed = fixed.read(),
        pivots = node.pivots,
        workOps = node.workOps,
        overheadOps = SumResult(overheadOpsTotal.toDouble()),
        workAllowanceSpent = workAllowanceSpent,
        demoted = demoted,
        luMaxFill = luMaxFill.read(),
        luMaxDensity = luMaxDensity.read(),
        luMaxDim = luMaxDim.read(),
        cuts = cuts.read(),
        cutCandidates = SumResult(cutCandidates.toDouble()),
        cutSelected = SumResult(cutSelected.toDouble()),
        cutActive = cutActive.read(),
        rootReducedCostFixes = SumResult(rootReducedCostFixes.toDouble()),
        backjumps = backjumps.read(),
        seeded = node.warmStartHits,
        refactorizations = SumResult(routeMetrics[LpRoute.NODE.ordinal].refactorizations.toDouble()),
        warmStartAttempts = node.warmStartAttempts,
        warmStartHits = node.warmStartHits,
        initialRefactorizations = node.initialRefactorizations,
        warmStartRefactorizations = node.warmStartRefactorizations,
        singularRecoveryRefactorizations = node.singularRecoveryRefactorizations,
        updateLimitRefactorizations = node.updateLimitRefactorizations,
        backendRequestedRefactorizations = node.backendRequestedRefactorizations,
        reconcileRecoveryRefactorizations = node.reconcileRecoveryRefactorizations,
        numericalRecoveryRefactorizations = node.numericalRecoveryRefactorizations,
        primalRefactorizations = node.primalRefactorizations,
        componentSplits = componentSplits.read(),
        componentBlocks = componentBlocks.read(),
        singularRefactorizations = node.singularRefactorizations,
        smallPivotBails = node.smallPivotBails,
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
        basisVerification = basisVerification,
        continuation = continuation,
        exactFarkasRay = certifierStats(LpCertifier.EXACT_FARKAS),
        exactPointFeasible = certifierStats(LpCertifier.EXACT_POINT),
        rationalOutcome = certifierStats(LpCertifier.RATIONAL),
        exactInputAttempts = SumResult(exactInputAttempts.toDouble()),
        exactInputRejections = SumResult(exactInputRejections.toDouble()),
    )

    private fun certifierStats(certifier: LpCertifier): LpCertifierStats = certifiers[certifier.ordinal].snapshot()
}

private class LpRouteSolveStatsSink(private val node: Boolean = false) {
    private var passes = 0L
    private var pivots = 0L
    private var workOps = 0L
    val work: Long get() = workOps
    private var warmAttempts = 0L
    private var warmHits = 0L
    private var singularRefactorizations = 0L
    private var smallPivotBails = 0L
    private var initialRefactorizations = 0L
    private var warmStartRefactorizations = 0L
    private var singularRecoveryRefactorizations = 0L
    private var updateLimitRefactorizations = 0L
    private var backendRequestedRefactorizations = 0L
    private var reconcileRecoveryRefactorizations = 0L
    private var numericalRecoveryRefactorizations = 0L
    private var primalRefactorizations = 0L

    fun observe(value: LpSolveMetrics, finished: Boolean) {
        if (finished) passes++
        pivots += value.pivots
        if (node) observeWork(value.workOps) else workOps += value.workOps
        warmAttempts += value.warmAttempts
        warmHits += value.warmHits
        singularRefactorizations += value.singularRefactorizations
        smallPivotBails += value.smallPivotBails
        initialRefactorizations += value.initialRefactorizations
        warmStartRefactorizations += value.warmStartRefactorizations
        singularRecoveryRefactorizations += value.singularRecoveryRefactorizations
        updateLimitRefactorizations += value.updateLimitRefactorizations
        backendRequestedRefactorizations += value.backendRequestedRefactorizations
        reconcileRecoveryRefactorizations += value.reconcileRecoveryRefactorizations
        numericalRecoveryRefactorizations += value.numericalRecoveryRefactorizations
        primalRefactorizations += value.primalRefactorizations
    }

    fun observeWork(ops: Long) {
        if (ops > 0L) workOps += ops
    }

    val refactorizations: Long
        get() = initialRefactorizations + warmStartRefactorizations + singularRecoveryRefactorizations +
            updateLimitRefactorizations + backendRequestedRefactorizations + reconcileRecoveryRefactorizations +
            numericalRecoveryRefactorizations + primalRefactorizations

    fun snapshot(other: LpRouteSolveStatsSink? = null) = LpRouteSolveStats(
        passes = SumResult((passes + (other?.passes ?: 0L)).toDouble()),
        pivots = SumResult((pivots + (other?.pivots ?: 0L)).toDouble()),
        workOps = SumResult((workOps + (other?.workOps ?: 0L)).toDouble()),
        warmStartAttempts = SumResult((warmAttempts + (other?.warmAttempts ?: 0L)).toDouble()),
        warmStartHits = SumResult((warmHits + (other?.warmHits ?: 0L)).toDouble()),
        initialRefactorizations = SumResult(
            (initialRefactorizations + (other?.initialRefactorizations ?: 0L)).toDouble(),
        ),
        warmStartRefactorizations = SumResult(
            (warmStartRefactorizations + (other?.warmStartRefactorizations ?: 0L)).toDouble(),
        ),
        singularRecoveryRefactorizations = SumResult(
            (singularRecoveryRefactorizations + (other?.singularRecoveryRefactorizations ?: 0L)).toDouble(),
        ),
        updateLimitRefactorizations = SumResult(
            (updateLimitRefactorizations + (other?.updateLimitRefactorizations ?: 0L)).toDouble(),
        ),
        backendRequestedRefactorizations = SumResult(
            (backendRequestedRefactorizations + (other?.backendRequestedRefactorizations ?: 0L)).toDouble(),
        ),
        reconcileRecoveryRefactorizations = SumResult(
            (reconcileRecoveryRefactorizations + (other?.reconcileRecoveryRefactorizations ?: 0L)).toDouble(),
        ),
        numericalRecoveryRefactorizations = SumResult(
            (numericalRecoveryRefactorizations + (other?.numericalRecoveryRefactorizations ?: 0L)).toDouble(),
        ),
        primalRefactorizations = SumResult((primalRefactorizations + (other?.primalRefactorizations ?: 0L)).toDouble()),
        singularRefactorizations = SumResult(
            (singularRefactorizations + (other?.singularRefactorizations ?: 0L)).toDouble(),
        ),
        smallPivotBails = SumResult((smallPivotBails + (other?.smallPivotBails ?: 0L)).toDouble()),
    )
}

private class LpCertifierStatsSink {
    private val successes = LongArray(LpRoute.entries.size)
    private val declines = LongArray(LpRoute.entries.size)

    fun observe(route: LpRoute, success: Boolean) {
        if (success) successes[route.ordinal]++ else declines[route.ordinal]++
    }

    fun snapshot(): LpCertifierStats {
        val successCount = successes.sum()
        val declineCount = declines.sum()
        return LpCertifierStats(
            attempts = SumResult((successCount + declineCount).toDouble()),
            successes = SumResult(successCount.toDouble()),
            declines = SumResult(declineCount.toDouble()),
            node = snapshot(LpRoute.NODE),
            standalone = snapshot(LpRoute.STANDALONE),
            component = snapshot(LpRoute.COMPONENT),
            root = snapshot(LpRoute.ROOT),
        )
    }

    private fun snapshot(route: LpRoute): LpCertifierRouteStats {
        val successCount = successes[route.ordinal]
        val declineCount = declines[route.ordinal]
        return LpCertifierRouteStats(
            SumResult((successCount + declineCount).toDouble()),
            SumResult(successCount.toDouble()),
            SumResult(declineCount.toDouble()),
        )
    }
}
