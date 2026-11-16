package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.cut.CutContext
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.cut.CutSeparator
import com.eignex.klause.lp.engine.ComponentLpSolverCapability
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.FloatDeductionCertificate
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPhaseTimer
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.lp.engine.ReconstructionLimits
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.engine.ceilLong
import com.eignex.klause.lp.engine.certifiedTightObjectiveLowerBound
import com.eignex.klause.lp.engine.certifyLpBoundOnly
import com.eignex.klause.lp.engine.certifyLpIntegerBoundOnly
import com.eignex.klause.lp.engine.hasIntegralObjective
import com.eignex.klause.lp.engine.lowerBoundDouble
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.withCpBounds
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.pollStop
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.CheckedLongOverflowException
import kotlin.math.abs
import kotlin.math.nextDown
import kotlin.math.round

/**
 * The root-node LP relaxation objective (with the harvested [globalCuts]) on the undecided problem,
 * or NaN when the relaxation is empty / not optimal / overflows — the revised simplex +
 * [certifiedTightObjectiveLowerBound], the same sound bound the per-node prune reports. Solved once before search,
 * so the value is a sound *global* lower bound on the objective — the integrality-gap baseline for `-s`.
 */
internal fun LpEngine.rootLpRelaxationBound(
    relaxer: CpToLpRelaxation,
    globalCuts: List<Cut>,
    cancellation: ExecutionControl = ExecutionControl.Never,
): Double = rootLpRelaxationBound(relaxer, globalCuts, cancellation, cancellation)

internal fun LpEngine.rootLpRelaxationBound(
    relaxer: CpToLpRelaxation,
    globalCuts: List<Cut>,
    cancellation: ExecutionControl,
    preparationCancellation: ExecutionControl,
): Double {
    val timer = LpPhaseTimer(sink.lp.certificationObserver(LpRoute.ROOT), LpSolvePhase.ROOT_QUERY)
    val before = totalSolveWork()
    val route = when {
        params.lpPlan.componentOwnership -> "COMPONENT"
        retainedRootEnabled -> "SPARSE"
        else -> "STANDALONE"
    }
    var stage = "ENTRY"
    return try {
        if (retainedRootEnabled) {
            retainedRootLpRelaxationBound(relaxer, globalCuts, cancellation, preparationCancellation) { stage = it }
        } else {
            stage = "AT_SOURCE_LAYOUT"
            standaloneRootLpRelaxationBound(relaxer, globalCuts, cancellation).also {
                stage = if (it.isNaN()) "DECLINED" else "BOUND"
            }
        }
    } catch (_: CheckedLongOverflowException) {
        stage = "SCALAR_WIDTH"
        Double.NaN
    } catch (_: LpAssemblyCancelled) {
        stage = "ASSEMBLY_CANCELLED"
        Double.NaN
    } finally {
        timer.finish("${route}_$stage", totalSolveWork() - before)
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // Root ownership cleanup covers every admission boundary.
private fun LpEngine.retainedRootLpRelaxationBound(
    relaxer: CpToLpRelaxation,
    globalCuts: List<Cut>,
    cancellation: ExecutionControl,
    preparationCancellation: ExecutionControl,
    observeStage: (String) -> Unit,
): Double {
    if (retainedRootRemainingWork() <= 0L) {
        observeStage("NO_INITIAL_WORK")
        return Double.NaN
    }
    if (cancellation.pollStop()) {
        observeStage("CANCELLED_INITIAL")
        return Double.NaN
    }
    // Cut owners retain propagation-derived bounds across root-to-node adoption.
    val root = if (globalCuts.isEmpty()) null else {
        observeStage("AT_ROOT_PROPAGATION")
        PropagationSession(problem, cancellation)
    }
    observeStage("AT_SOURCE_LAYOUT")
    val relaxation = (if (root == null) {
        val preparation = preparationCancellation or params.cancellation or
            ExecutionControl { retainedRootRemainingWork() <= 0L }
        sourceRootRelaxation(relaxer, globalCuts, preparation)
    } else nodeRelaxation(relaxer, root, globalCuts)) ?: run {
        if (sparseTrialActive) sink.lp.sparseTrial = sink.lp.sparseTrial + ("RootSourceLayoutDeclined" to 1L)
        cpAdapter.reset()
        if (retainedRootRemainingWork() <= 0L) {
            observeStage("SOURCE_LAYOUT_NO_WORK")
            return Double.NaN
        }
        if (cancellation.pollStop()) {
            observeStage("SOURCE_LAYOUT_CANCELLED")
            return Double.NaN
        }
        if (sparseTrialActive) sink.lp.sparseTrial = sink.lp.sparseTrial + ("StandaloneRootFallback" to 1L)
        observeStage("AT_FALLBACK")
        return standaloneRootLpRelaxationBound(relaxer, globalCuts, cancellation).also {
            observeStage(if (it.isNaN()) "FALLBACK_DECLINED" else "FALLBACK_BOUND")
        }
    }
    if (relaxation.model.n == 0) {
        observeStage("EMPTY_MODEL")
        return Double.NaN
    }
    val continuation = if (sparseTrialActive) ::retainedRootRemainingWork else null
    retainRootPrimalOwner(relaxation)
    var pausedBound = Double.NaN
    try {
        while (retainedRootRemainingWork() > 0L && !cancellation.pollStop()) {
            observeStage("AT_NUMERICAL_QUERY")
            val attempt = solveNode(relaxation.model, null, cancellation, continuation)
            if (attempt == null) {
                val metrics = propagator.lastMetrics.copy(preparationOps = 0L)
                observeRootWork(metrics, finished = !propagator.floatPaused)
                observeSparsePausedProgress()
                if (!propagator.floatPaused || metrics.workOps == 0L) {
                    observeStage(if (!pausedBound.isNaN()) "PAUSED_BOUND" else
                        "QUERY_DECLINED_${propagator.floatTermination?.name ?: "NO_TERMINATION"}")
                    return pausedBound
                }
                if (sparseTrialActive) sink.lp.sparseTrial = sink.lp.sparseTrial +
                    ("RootPauses" to ((sink.lp.sparseTrial["RootPauses"] ?: 0L) + 1L))
                observeNodePause()
                if (sparseTrialActive && ownsRootPrimalModel(relaxation.model)) {
                    observeStage("AT_PAUSED_CERTIFICATION")
                    val lower = retainedRootLowerBound(relaxation.model, null, cancellation, pausedProposal = true)
                    val bound = if (cancellation.pollStop()) Double
                        .NaN else projectCertifiedLowerBound(relaxation, lower)
                    if (!bound.isNaN() && !cancellation.pollStop()) {
                        pausedBound = if (pausedBound.isNaN()) bound else maxOf(pausedBound, bound)
                        sink.lp.sparseTrial = sink.lp.sparseTrial + ("PausedCertifiedBounds" to
                            ((sink.lp.sparseTrial["PausedCertifiedBounds"] ?: 0L) + 1L))
                    }
                }
                continue
            }
            // Scoped preparation has already charged its scaling-construction reservation.
            val metrics = if (nodeUsesTrail) propagator.lastMetrics.copy(preparationOps = 0L)
            else attempt.first.lastMetrics
            observeRootSolve(attempt.first, metrics)
            val component = attempt.first as? ComponentLpSolverCapability
            if (attempt.second?.optimal != true) {
                retainRootPartialPrimalGuidance(relaxation, component, cancellation)
            }
            observeStage("AT_CERTIFICATION")
            val lower = retainedRootLowerBound(
                relaxation.model, attempt.second, cancellation,
                component,
            )
            retainRootPrimalGuidance(relaxation, attempt.second, cancellation)
            val stopped = cancellation.pollStop()
            val bound = if (stopped) Double.NaN else projectCertifiedLowerBound(relaxation, lower)
            observeStage(
                when {
                    bound.isNaN() && !pausedBound.isNaN() -> "PAUSED_BOUND"
                    stopped -> "CANCELLED_AFTER_QUERY"
                    attempt.second == null && !bound.isNaN() -> "PARTIAL_BOUND"
                    attempt.second == null -> "NO_FLOAT_${attempt.first.lastTermination?.name ?: "NO_TERMINATION"}"
                    lower == null -> "CERTIFICATE_DECLINED"
                    bound.isNaN() -> "SOURCE_PROJECTION_DECLINED"
                    else -> "BOUND"
                },
            )
            return if (pausedBound.isNaN()) bound else if (bound.isNaN()) pausedBound else
                maxOf(pausedBound, bound)
        }
        sink.lp.observePrimalPhase("ROOT_QUERY_STOP", rootQueryStopView(), 0L)
        observeStage(if (pausedBound.isNaN()) "QUERY_LOOP_STOPPED" else "PAUSED_BOUND")
        return pausedBound
    } finally {
        val retained = cpAdapter.retainRootProbe(root)
        if (sparseTrialActive) {
            sink.lp.sparseTrial = sink.lp.sparseTrial + ("RootOwnerRetained" to if (retained) 1L else 0L)
        }
    }
}

private fun LpEngine.standaloneRootLpRelaxationBound(
    relaxer: CpToLpRelaxation,
    globalCuts: List<Cut>,
    cancellation: ExecutionControl,
): Double {
    val relaxation = relaxer.build(PropagationSession(problem), globalCuts, cancellation)
    return if (relaxation.model.n == 0) {
        Double.NaN
    } else {
        val simplex = dualSimplex(relaxation.model, cancellation)
        val result = try {
            simplex.solve()
        } finally {
            try {
                observeRootSolve(simplex)
            } finally {
                simplex.close()
            }
        }
        val lower = result?.let {
            if (retainedRootEnabled) {
                retainedRootLowerBound(relaxation.model, it, cancellation)
            } else {
                objectiveLowerBound(relaxation.model, it.duals, rootCertificationObserver())
            }
        }
        retainRootPrimalGuidance(relaxation, result, cancellation)
        projectCertifiedLowerBound(relaxation, lower)
    }
}

private fun LpEngine.retainedRootLowerBound(
    model: LpModel,
    result: FloatLpResult?,
    cancellation: ExecutionControl,
    component: ComponentLpSolverCapability? = null,
    pausedProposal: Boolean = false,
): Double? {
    if (result == null && component == null && !pausedProposal) return null
    val projectionWork = model.extent() * LpNodeOverhead.EXACT
    val snapshotWork = if (pausedProposal) 8L * model.m else 0L
    if (retainedRootRemainingWork() <= projectionWork + snapshotWork || cancellation.pollStop()) return null
    // Source-range and objective projection scans share the existing whole-model overhead charge.
    if (!pausedProposal && !numericalDeductions) model.alsoCharged(this)
    val defaults = ReconstructionLimits()
    val storage = if (sparseTrialActive) sink.lp.sparseTrial["CheckingReservedBytes"] ?: 0L else
        SPARSE_LP_CHECKING_ALLOCATION
    val allocation = minOf(defaults.maxAllocation, storage)
    val candidateBytes = if (result == null) model.m.toLong() * Double.SIZE_BYTES else 0L
    if (candidateBytes > allocation) return null
    val proposal = if (pausedProposal) {
        noteNodeOverhead(snapshotWork)
        val copied = propagator.pausedDualProposal(cancellation) ?: return null
        if (cancellation.pollStop()) return null
        if (!numericalDeductions) model.alsoCharged(this)
        copied
    } else null
    val remaining = retainedRootRemainingWork()
    val duals = result?.duals ?: proposal ?: component?.partialBoundDuals(cancellation) ?: return null
    if (duals.size != model.m || cancellation.pollStop()) return null
    val checked = checkRetainedRootBound(
        model,
        duals,
        defaults.copy(
            maxCoordinates = model.numVars,
            maxEntries = (model.doubleView?.colPtr ?: model.csc.colPtr)[model.n] + model.m,
            maxWork = remaining,
            maxAllocation = allocation - candidateBytes,
        ),
        cancellation,
    ) ?: return null
    if (cancellation.pollStop()) return null
    val lower = if (model.hasIntegralObjective()) {
        checked.ceilLong()?.let(BigFraction::ofLong) ?: checked
    } else checked
    return lower.lowerBoundDouble()
}

private fun LpEngine.checkRetainedRootBound(
    model: LpModel,
    duals: DoubleArray,
    limits: ReconstructionLimits,
    cancellation: ExecutionControl,
): BigFraction? {
    if (numericalDeductions) return FloatDeductionCertificate.of(model, duals)?.lowerBound?.let(BigFraction::ofDouble)
    val observer = rootCertificationObserver()
    val integer = if (model.exactState != null) {
        certifyLpIntegerBoundOnly(model, duals, limits, cancellation, observer, solveContext.certificationPolicy)
    } else null
    integer?.bound?.let { return it.value }
    if (cancellation.pollStop()) return null
    val work = minOf(retainedRootRemainingWork(), limits.maxWork - (integer?.metrics?.work ?: 0L))
    val allocation = limits.maxAllocation - (integer?.metrics?.allocation ?: 0L)
    if (work <= 0L || allocation <= 0L) return null
    return certifyLpBoundOnly(
        model, duals, limits.copy(maxWork = work, maxAllocation = allocation), cancellation,
        observer, solveContext.certificationPolicy,
    ).bound?.value
}

internal fun LpEngine.recordRootQueryBound(relaxation: LpRelaxation, result: FloatLpResult) {
    if (!measuresRootQueries) return
    val lower = objectiveLowerBound(
        relaxation.model, result.duals, sink.lp.certificationObserver(LpRoute.NODE), charged = true,
    )
    if (!params.cancellation()) recordRootLowerBound(projectCertifiedLowerBound(relaxation, lower))
}

private fun LpEngine.projectCertifiedLowerBound(relaxation: LpRelaxation, lower: Double?): Double {
    val full = lower?.let { BigFraction.ofDouble(it) }?.plus(BigFraction.ofLong(relaxation.objectiveConstant))
    val projected = full?.lowerBoundDouble() ?: return Double.NaN
    val integral = relaxation.model.hasIntegralObjective()
    return when {
        integral && sourceObjectiveRange(relaxation) == null -> Double.NaN
        integral && abs(projected) >= EXACT_INTEGER_DOUBLE_LIMIT -> projected.nextDown()
        else -> projected
    }
}

/**
 * The **true** root LP optimum (the float simplex objective, plus the relaxation's objective constant),
 * or NaN when the relaxation is empty / not optimal / overflows. Unlike [rootLpRelaxationBound] this is
 * the raw LP value, not a sound under-estimate — so it reflects how much a hull actually tightens the
 * relaxation, which the sound bound can miss. Used only to compare relaxation variants in
 * [LpEngine.pruneIneffectiveHulls], never as a sound prune bound.
 */
internal fun LpEngine.rootLpObjective(
    relaxer: CpToLpRelaxation,
    cancellation: ExecutionControl = ExecutionControl.Never,
): Double = try {
    val relaxation = relaxer.build(PropagationSession(problem), cancellation = cancellation)
    if (relaxation.model.n == 0) {
        Double.NaN
    } else {
        val simplex = dualSimplex(relaxation.model, cancellation)
        val result = try {
            simplex.solve()
        } finally {
            try {
                observeRootSolve(simplex)
            } finally {
                simplex.close()
            }
        }
        if (result != null) result.objective + relaxation.objectiveConstant.toDouble() else Double.NaN
    }
} catch (_: CheckedLongOverflowException) {
    Double.NaN
} catch (_: LpAssemblyCancelled) {
    Double.NaN
}

/**
 * Harvest a persistent pool of **global** cuts from the root relaxation on the sparse
 * revised-simplex path. Each round solves the (cut-augmented) root LP, separates violated cuts from the
 * LP point, and keeps the fresh ones whose source proof or separator scope is global. A root box may
 * close a source-open side, so root separation alone does not make a cut global. A 64-bit overflow
 * keeps the global cuts already pooled.
 */
@Suppress("LongParameterList", "TooGenericExceptionCaught") // root ownership must preserve arbitrary solver failures
internal fun LpEngine.harvestRootCuts(
    relaxer: CpToLpRelaxation,
    session: PropagationSession,
    separators: List<CutSeparator>,
    gomory: Boolean,
    mir: Boolean,
    cancellation: ExecutionControl = ExecutionControl.Never,
): List<Cut> {
    if (session.isUnsatAtRoot) return emptyList()
    if (separators.isEmpty() && !gomory && !mir) return emptyList()
    beginCutAttribution(LpRoute.ROOT)
    sink.lp.cutLifecycle.families = separators.mapTo(HashSet()) { it.family }.also {
        if (gomory) it.add(CutFamily.GOMORY)
        if (mir) it.add(CutFamily.MIR)
    }
    fun pool(limit: Int = CutPool.DEFAULT_MAX_CUTS) = CutPool(maxCuts = limit,
        observe = { family, stage, outcome -> sink.lp.cutLifecycle.event(family, stage, outcome) })
    var pool = pool()
    try {
        var relaxation = sink.lp.cutLifecycle.measureShared("ASSEMBLY") {
            relaxer.build(session, cancellation = cancellation)
        }
        if (relaxation.model.n == 0) return emptyList()
        if (!cutSeparationSupported(relaxation, cancellation, root = true)) return emptyList()
        if (ordinaryCutSeparation) {
            pool = pool(minOf(CutPool.DEFAULT_MAX_CUTS, relaxation.model.m))
        }
        var simplex = dualSimplex(relaxation.model, cancellation)
        var ownedSimplex: TableauCutSolver? = simplex
        var primaryFailure: Throwable? = null
        try {
            val initial = try {
                simplex.solve()
            } finally {
                observeRootSolve(simplex)
            }
            var result = initial ?: return emptyList()
            var resultObserved = false
            var round = 0
            while (round++ < CUT_POOL_ROUNDS && !cancellation.pollStop()) {
                relaxation.sourceMap?.withCpBounds(relaxation.model, session)?.let(pool::remap)
                pool.observe(result.primal)
                resultObserved = true
                val ctx = CutContext(problem, relaxation, result.primal, session)
                // Keep only cuts justified beyond the root box; open source sides can have invented endpoints.
                val structural = separators.flatMap { constructCuts(it, ctx) }.mapNotNull {
                    when {
                        it.provenance != null || it.tableau != null -> mapGlobalCut(it, relaxation)
                        it.global -> it
                        else -> null
                    }
                }
                val gomoryCuts = if (gomory) sink.lp.cutLifecycle.measure(CutFamily.GOMORY, "CONSTRUCTION") {
                    simplex.gomoryCuts(GOMORY_CUTS_PER_ROUND).map { it.withFamily(CutFamily.GOMORY) }
                } else emptyList()
                val mirCuts = if (mir) sink.lp.cutLifecycle.measure(CutFamily.MIR, "CONSTRUCTION") {
                    simplex.mirCuts(GOMORY_CUTS_PER_ROUND).map { it.withFamily(CutFamily.MIR) }
                } else emptyList()
                val mappedTableau = (gomoryCuts + mirCuts).mapNotNull { mapGlobalCut(it, relaxation) }
                val candidates = structural + mappedTableau
                val added = candidates.count { pool.add(it, relaxation) }
                observeRootCutAccounting(candidates.size, 0, 0)
                if (added == 0) break
                if (ordinaryCutSeparation) {
                    pool.observe(result.primal)
                    pool.retainMostActive()
                }
                val selected = pool.cuts()
                sink.lp.cutLifecycle.families = selected.mapTo(HashSet()) { it.family }
                relaxation = observeRootCutBuild(selected.size) {
                    sink.lp.cutLifecycle.measureShared("ASSEMBLY") { relaxer.build(session, selected, cancellation) }
                }
                activateCutAttribution(relaxation)
                val replacement = dualSimplex(relaxation.model, cancellation)
                ownedSimplex = replacement
                sink.lp.cutLifecycle.measureShared("RETIREMENT") { simplex.close() }
                simplex = replacement
                val next = try {
                    simplex.solve()
                } finally {
                    observeRootSolve(simplex)
                }
                if (next == null) break
                observeCutFill(next.luMaxFill, relaxation.model.m)
                result = next
                resultObserved = false
            }
            // Bound the pool the search nodes inherit by per-cut activity (tightness at the final LP point):
            // a large harvest is trimmed to the most-active cuts, the rest evicted (sound — all global).
            if (!resultObserved) pool.observe(result.primal)
            pool.retainMostActive()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val owned = ownedSimplex
            ownedSimplex = null
            if (primaryFailure == null) {
                sink.lp.cutLifecycle.measureShared("RETIREMENT") { owned?.close() }
            } else {
                try {
                    sink.lp.cutLifecycle.measureShared("RETIREMENT") { owned?.close() }
                } catch (closeFailure: Throwable) {
                    primaryFailure.addSuppressed(closeFailure)
                }
            }
        }
    } catch (_: CheckedLongOverflowException) {
        return pool.cuts() // keep whatever stayed within 64-bit determinants — still globally valid
    } catch (_: LpAssemblyCancelled) {
        return pool.cuts()
    } finally {
        sink.lp.cutLifecycle.families = emptySet()
        sink.lp.cutLifecycle.recipe = LpRoute.NODE
    }
    return pool.cuts()
}

/** Most Gomory cuts to draw from one tableau per separation round. */
internal const val GOMORY_CUTS_PER_ROUND: Int = 8

private fun objectiveCoefficients(model: LpModel): DoubleArray = DoubleArray(model.n) { model.costD(it) }

/** Separation rounds when harvesting the persistent root cut pool. */
internal const val CUT_POOL_ROUNDS: Int = 8

/** Separation rounds per during-search node — fewer than the root harvest, since the node solve
 *  repeats deeper in the tree. */
internal const val SEARCH_CUT_ROUNDS: Int = 4

/**
 * [RootRelaxationSize.cost] ceiling for per-candidate shave, redundancy and equality probes.
 * Bounds the cumulative cost of these solves during the root harvest.
 */
internal const val LP_HARVEST_MAX_RELAXATION_COST = 250_000L
