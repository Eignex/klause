package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration
import kotlin.time.TimeSource.Monotonic

internal enum class LpReplayOperation { SOLVE, SOLVE_PRIMAL, REBIND, RESOLVE_BOUNDS, RESOLVE_GATED }

internal enum class LpCandidateKind { NONE, FLOAT_OPTIMUM, FLOAT_BOUND, FLOAT_INFEASIBILITY }

internal enum class LpIndependentValidation { VALIDATED, DECLINED, REFUTED }

internal enum class LpStrictReplayCapability {
    NOT_REQUIRED,
    PENDING,
    ELIGIBLE,
    UNSUPPORTED_ROUTE,
    POLICY_DECLINED,
    ADMISSION_DECLINED,
}

internal enum class LpCertificationCapability { ELIGIBLE, GATED_ACTIVE_STATE_UNAVAILABLE, NO_CLAIM }

internal enum class LpIndependentClaim {
    NONE,
    CANDIDATE_HINT,
    FEASIBLE_WITNESS,
    CERTIFIED_BOUND,
    PROVED_OPTIMUM,
    PROVED_INFEASIBLE,
}

internal class LpIndependentCheck(val validation: LpIndependentValidation, val claim: LpIndependentClaim)

internal class LpReplayCertificationCount(val certifier: LpCertifier, val attempts: Int, val successes: Int) {
    val declines: Int get() = attempts - successes
}

internal class LpReplayStep(
    val eventIndex: Int,
    val operation: LpReplayOperation,
    val candidate: LpCandidateKind,
    val productionVerdict: LpVerdict?,
    val objectiveBits: Long?,
    val primalBits: LongArray?,
    val integerObjectiveLowerBound: Long?,
    val hasFeasibleWitness: Boolean,
    val hasCertifiedBound: Boolean,
    val hasInfeasibilityProof: Boolean,
    val metrics: LpSolveMetrics,
    val certifiers: List<LpReplayCertificationCount>,
    val exactInputAttempts: Int,
    val exactInputAccepted: Int,
    val certificationCapability: LpCertificationCapability = LpCertificationCapability.ELIGIBLE,
    val enforcedRows: BooleanArray? = null,
    val exactWitness: List<BigFraction>? = null,
    val rationalLowerBound: BigFraction? = null,
    val refinement: LpRefinementMetrics? = null,
    val continuation: ExactContinuationMetrics? = null,
    val supplementalEvidenceReused: Boolean = false,
    val strictRefinementCapability: LpStrictReplayCapability = LpStrictReplayCapability.NOT_REQUIRED,
    var independentCheck: LpIndependentCheck = LpIndependentCheck(
        LpIndependentValidation.DECLINED,
        if (candidate == LpCandidateKind.NONE) LpIndependentClaim.NONE else LpIndependentClaim.CANDIDATE_HINT,
    ),
)

internal class LpReplayReport(val label: String, val seed: Long, val steps: List<LpReplayStep>) {
    val pivots: Int get() = steps.sumOf { it.metrics.pivots }
    val workOps: Long get() = steps.fold(0L) { total, step -> total + step.metrics.workOps }
    val validationEligible: Int get() = steps.count {
        it.productionVerdict != null && it.certificationCapability == LpCertificationCapability.ELIGIBLE
    }
    val validationPassed: Int get() = steps.count {
        it.independentCheck.validation == LpIndependentValidation.VALIDATED
    }
    val validationDeclined: Int get() = steps.count {
        it.independentCheck.validation == LpIndependentValidation.DECLINED
    }
    val validationRefuted: Int get() = steps.count { it.independentCheck.validation == LpIndependentValidation.REFUTED }
}

internal fun interface LpReplayValidator {
    fun validate(model: LpModel, step: LpReplayStep): LpIndependentCheck
}

/** Executes captures through the engine factories and interfaces. Capability and event validation is
 * completed before the single solver is created. Unsupported future state transitions are explicit
 * failures; in particular, gated float candidates cannot be certified until certifiers accept an active
 * row mask. Returned bases are copied discrete warm-start hints, never portable factor snapshots. */
internal object LpReplay {
    fun replay(
        capture: LpCapture,
        validator: LpReplayValidator? = null,
        context: LpSolveContext = LpSolveContext.Production,
        continuationLimits: ExactContinuationLimits = ExactContinuationLimits(),
    ): LpReplayReport {
        validateForReplay(capture)
        var model = capture.model.toModel()
        val settings = capture.settings
        var cancellation: Cancellation = PollBudgetCancellation(settings.cancellationPollLimit)
        val boundUpdates = if (capture.events.any { it is LpReplayEvent.Rebind }) ReplayBoundUpdates(model) else null
        val solver = newSolver(boundUpdates?.working ?: model, settings, cancellation, context)
        val steps = ArrayList<LpReplayStep>(capture.events.size)
        val continuationBudget = ReplayContinuationBudget(continuationLimits)
        solver.use {
            ReplayStrictCertification(model, settings, context).use { strict ->
                capture.events.forEachIndexed { index, event ->
                    val step = when (event) {
                        is LpReplayEvent.Solve -> if (boundUpdates?.restore(solver, cancellation) == false) {
                            declinedStep(index, LpReplayOperation.SOLVE)
                        } else {
                            replaySolve(
                                index,
                                LpReplayOperation.SOLVE,
                                boundUpdates?.working ?: model,
                                solver,
                                cancellation,
                                context,
                                strict,
                                continuationBudget,
                            ) { solver.solve(event.warm?.toBasis(model.hasUpper, model.m)) }
                        }

                        is LpReplayEvent.SolvePrimal -> if (boundUpdates?.restore(solver, cancellation) == false) {
                            declinedStep(index, LpReplayOperation.SOLVE_PRIMAL)
                        } else {
                            replaySolve(
                                index,
                                LpReplayOperation.SOLVE_PRIMAL,
                                boundUpdates?.working ?: model,
                                solver,
                                cancellation,
                                context,
                                strict,
                                continuationBudget,
                            ) { solver.solvePrimal(event.warm?.toBasis(model.hasUpper, model.m)) }
                        }

                        is LpReplayEvent.Rebind -> {
                            val next = model.rebind(event.lo, event.hi)
                            if (event.cancellationPollLimit != null) {
                                cancellation = PollBudgetCancellation(event.cancellationPollLimit)
                            }
                            checkNotNull(boundUpdates).replace(next, solver, cancellation)
                            model = next
                            emptyStep(index, LpReplayOperation.REBIND)
                        }

                        is LpReplayEvent.ResolveBounds -> if (boundUpdates?.restore(solver, cancellation) == false) {
                            declinedStep(index, LpReplayOperation.RESOLVE_BOUNDS)
                        } else {
                            replaySolve(
                                index,
                                LpReplayOperation.RESOLVE_BOUNDS,
                                boundUpdates?.working ?: model,
                                solver,
                                cancellation,
                                context,
                                strict,
                                continuationBudget,
                            ) { (solver as PersistentLpSolver).resolveBounds() }
                        }

                        is LpReplayEvent.ResolveGated -> if (
                            boundUpdates?.mask(event.enforced, solver, cancellation) == false
                        ) {
                            declinedStep(index, LpReplayOperation.RESOLVE_GATED, event.enforced)
                        } else {
                            replayUncertified(
                                index,
                                LpReplayOperation.RESOLVE_GATED,
                                solver,
                                event.enforced,
                            ) {
                                if (boundUpdates == null) {
                                    (solver as PersistentLpSolver).resolveGated(event.enforced.copyOf())
                                } else {
                                    (solver as PersistentLpSolver).resolveBounds()
                                }
                            }
                        }

                        else -> error("unsupported event passed replay preflight: ${event::class.simpleName}")
                    }
                    step.independentCheck = validator?.validate(model, step) ?: step.independentCheck
                    steps += step
                }
            }
        }
        return LpReplayReport(settings.label, settings.seed, steps)
    }

    private fun replaySolve(
        eventIndex: Int,
        operation: LpReplayOperation,
        model: LpModel,
        solver: LpSolver,
        cancellation: Cancellation,
        context: LpSolveContext,
        strict: ReplayStrictCertification?,
        continuationBudget: ReplayContinuationBudget,
        solve: () -> FloatLpResult?,
    ): LpReplayStep {
        val observer = ReplayObserver(continuationBudget)
        val result = solve()
        val metrics = solver.lastMetrics
        val certificationStarted = Monotonic.markNow()
        val direct = certifyLpResult(
            model,
            solver,
            result,
            cancellation,
            observer,
            context.certificationPolicy,
            continuationCache = continuationBudget.cache,
            continuationLimits = continuationBudget.limits(),
        )
        val certified = strict?.certify(
            direct,
            result,
            solver,
            cancellation,
            observer,
            certificationStarted.elapsedNow(),
        ) ?: direct
        return LpReplayStep(
            eventIndex,
            operation,
            when {
                result == null && solver.infeasibleRay != null -> LpCandidateKind.FLOAT_INFEASIBILITY
                result == null -> LpCandidateKind.NONE
                result.optimal -> LpCandidateKind.FLOAT_OPTIMUM
                else -> LpCandidateKind.FLOAT_BOUND
            },
            certified.verdict,
            result?.objective?.toRawBits(),
            certified.exactPrimal?.map { it.toDouble().toRawBits() }?.toLongArray() ?: result?.primal?.toRawBitsArray(),
            certified.integerObjectiveLowerBound,
            certified.witness != null,
            certified.bound != null,
            certified.verdict == LpVerdict.INFEASIBLE,
            metrics,
            observer.counts(),
            observer.exactInputAttempts,
            observer.exactInputAccepted,
            exactWitness = certified.exactPrimal,
            rationalLowerBound = certified.lowerBound,
            refinement = strict?.lastMetrics,
            continuation = observer.continuation,
            supplementalEvidenceReused = strict?.reused == true,
            strictRefinementCapability = strict?.capability ?: LpStrictReplayCapability.NOT_REQUIRED,
        )
    }

    private fun replayUncertified(
        eventIndex: Int,
        operation: LpReplayOperation,
        solver: LpSolver,
        enforcedRows: BooleanArray,
        solve: () -> FloatLpResult?,
    ): LpReplayStep {
        val result = solve()
        return LpReplayStep(
            eventIndex,
            operation,
            when {
                result == null && solver.infeasibleRay != null -> LpCandidateKind.FLOAT_INFEASIBILITY
                result == null -> LpCandidateKind.NONE
                result.optimal -> LpCandidateKind.FLOAT_OPTIMUM
                else -> LpCandidateKind.FLOAT_BOUND
            },
            LpVerdict.INDETERMINATE,
            result?.objective?.toRawBits(),
            result?.primal?.toRawBitsArray(),
            null,
            false,
            false,
            false,
            solver.lastMetrics,
            emptyList(),
            0,
            0,
            LpCertificationCapability.GATED_ACTIVE_STATE_UNAVAILABLE,
            enforcedRows.copyOf(),
        )
    }

    private fun emptyStep(index: Int, operation: LpReplayOperation): LpReplayStep = LpReplayStep(
        index,
        operation,
        LpCandidateKind.NONE,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        LpSolveMetrics(),
        emptyList(),
        0,
        0,
        LpCertificationCapability.NO_CLAIM,
    )

    private fun declinedStep(index: Int, operation: LpReplayOperation, enforced: BooleanArray? = null): LpReplayStep =
        LpReplayStep(
            index, operation, LpCandidateKind.NONE, LpVerdict.INDETERMINATE,
            null, null, null, false, false, false, LpSolveMetrics(), emptyList(), 0, 0,
            if (enforced == null) {
                LpCertificationCapability.NO_CLAIM
            } else {
                LpCertificationCapability.GATED_ACTIVE_STATE_UNAVAILABLE
            },
            enforced?.copyOf(),
        )
}

private class ReplayBoundUpdates(model: LpModel) {
    private var full = LpExactState(requireNotNull(model.authoritativeModel()))
    private var revision = 0L
    private var available = true
    private var masked = false
    var working: LpModel = requireNotNull(full.toWorkingModel())
        private set

    fun replace(model: LpModel, solver: LpSolver, token: Cancellation) {
        val next = state(requireNotNull(model.authoritativeModel()))
        if (full.sameMatrix(next)) next.inheritProjection(full)
        full = next
        masked = false
        adopt(next, solver, token)
    }

    fun restore(solver: LpSolver, token: Cancellation): Boolean {
        if (!available || !masked) return available
        val next = state(full.baseModel)
        next.inheritProjection(full)
        full = next
        masked = false
        return adopt(next, solver, token)
    }

    fun mask(enforced: BooleanArray, solver: LpSolver, token: Cancellation): Boolean {
        if (!available) return false
        val rows = full.rows.deactivate(enforced.indices.filterTo(HashSet()) { !enforced[it] })
        val next = state(full.baseModel, rows)
        next.inheritProjection(full)
        masked = true
        return adopt(next, solver, token)
    }

    private fun state(model: ExactLpModel, rows: LpScopedRows = LpScopedRows.initial(model.m)): LpExactState {
        revision++
        return LpExactState(
            model,
            boundRevision = revision,
            popRevision = revision,
            rows = rows,
            rowRevision = revision,
        )
    }

    private fun adopt(state: LpExactState, solver: LpSolver, token: Cancellation): Boolean {
        // A rejected transition leaves the donor's result slots intact; none belong to the requested state.
        available = false
        if (!(solver as PersistentLpSolver).adopt(state, token)) return false
        working = requireNotNull(state.toWorkingModel())
        available = true
        return true
    }
}

private class ReplayStrictCertification(
    private val source: LpModel,
    private val settings: LpReplaySettings,
    private val context: LpSolveContext,
) : AutoCloseable {
    private var working: LpModel? = null
    private var anchor: LpScopedSolver? = null
    private var request: LpRefinementRequest? = null
    private var projectionWork = 0L
    private var projectionAllocation = 0L
    private var setupElapsed = Duration.ZERO
    var capability = when {
        !source.rowStrict.any { it } -> LpStrictReplayCapability.NOT_REQUIRED
        settings.solverKind != LpReplaySolverKind.GENERAL -> LpStrictReplayCapability.UNSUPPORTED_ROUTE
        context.certificationPolicy !== ProductionLpCertificationPolicy -> LpStrictReplayCapability.POLICY_DECLINED
        else -> LpStrictReplayCapability.PENDING
    }
        private set
    private var evidence: LpRefinementResult? = null
    var lastMetrics: LpRefinementMetrics? = null
        private set
    var reused: Boolean = false
        private set

    fun certify(
        direct: CertifiedLpResult,
        proposal: FloatLpResult?,
        solver: LpSolver,
        cancellation: Cancellation,
        observer: LpCertificationObserver,
        elapsed: Duration,
    ): CertifiedLpResult {
        lastMetrics = null
        reused = false
        if (direct.witness != null || direct.verdict == LpVerdict.INFEASIBLE || cancellation()) return direct
        if (!prepare(cancellation)) return direct
        val retained = evidence
        val recovered = retained ?: refineLp(
            checkNotNull(working),
            checkNotNull(request),
            proposal?.primal,
            proposal?.duals,
            proposal?.basis ?: solver.infeasibleBasis,
            cancellation = cancellation,
            directWork = projectionWork + (direct.reconstruction?.work ?: 0L) +
                (direct.basisVerification?.work ?: 0L) + (direct.continuation?.work ?: 0L),
            directAllocation = projectionAllocation + (direct.reconstruction?.allocation ?: 0L) +
                (direct.basisVerification?.allocation ?: 0L) + (direct.continuation?.allocation ?: 0L),
            directElapsed = elapsed + setupElapsed,
            reconstructInitial = direct.reconstruction == null,
        )
        if (retained == null) {
            lastMetrics = recovered.metrics
            observer.observe(LpCertifier.RATIONAL, recovered.witness != null || recovered.conflict != null)
        } else {
            reused = true
        }
        if (cancellation()) return direct
        val point = recovered.witness?.let { checkedLpWitness(source, it.primal) }
        val conflict = recovered.conflict?.takeIf { point == null && checkedLpConflict(source, it) }
        if ((point == null && conflict == null) || cancellation()) return direct
        val bound = if (conflict != null) {
            null
        } else {
            listOfNotNull(
                direct.bound,
                recovered.bound,
            ).reduceOrNull { a, b -> if (a.value > b.value) a else b }
        }
        val integral = source.hasIntegralObjective()
        if (cancellation()) return direct
        evidence = recovered
        return CertifiedLpResult(
            direct.float, bound, point, null, conflict, integral,
            { null },
            conflictSupport = recovered.support.takeIf { conflict != null },
            reconstruction = direct.reconstruction, basisVerification = direct.basisVerification,
            continuation = direct.continuation, refinement = lastMetrics,
        )
    }

    override fun close() {
        val current = anchor
        anchor = null
        current?.close()
    }

    private fun prepare(cancellation: Cancellation): Boolean {
        if (capability != LpStrictReplayCapability.PENDING) return capability == LpStrictReplayCapability.ELIGIBLE
        capability = LpStrictReplayCapability.ADMISSION_DECLINED
        val started = Monotonic.markNow()
        try {
            val entries = source.doubleView?.rowIdx?.size ?: source.csc.rowIdx.size
            if (source.numVars > 128 || entries > 512 || cancellation()) return false
            var premises = 0L
            for (row in source.rowPremises) {
                premises += (row?.vars?.size ?: 0).toLong() * 3L + (row?.boolLits?.size ?: 0)
                if (premises > 512L || cancellation()) return false
            }
            // Bounded Long/IEEE mapping and metadata copies precede the metered vector projection.
            projectionWork = 4096L + 32L * (source.numVars + entries + premises)
            projectionAllocation = 1_048_576L + 4096L * (source.numVars + entries + premises)
            val model = source.authoritativeModel() ?: return false
            val state = LpExactState(model)
            val projection = LpProjectionMeter(
                workLimit = 1_000_000L,
                allocationLimit = 16_777_216L,
                cancellation = cancellation,
            )
            val projected = try {
                state.toWorkingModel(projection)
            } finally {
                projectionWork += projection.work
                projectionAllocation += projection.allocation
            } ?: return false
            if (cancellation()) return false
            val owner = LpScopedSolver(state, cancellation, context)
            anchor = owner
            working = projected
            request = LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits())
            capability = LpStrictReplayCapability.ELIGIBLE
            return true
        } finally {
            setupElapsed = started.elapsedNow()
        }
    }
}

private fun newSolver(
    model: LpModel,
    settings: LpReplaySettings,
    cancellation: Cancellation,
    context: LpSolveContext,
): LpSolver = when (settings.solverKind) {
    LpReplaySolverKind.GENERAL -> newLpSolver(model, cancellation, settings.componentSplit, context.engineFactory)

    LpReplaySolverKind.TABLEAU -> newTableauCutSolver(
        model,
        cancellation,
        settings.pivotLimit,
        settings.workLimit,
        settings.trackDegeneracy,
        factory = context.engineFactory,
    )

    LpReplaySolverKind.PERSISTENT -> newPersistentLpSolver(
        model,
        cancellation,
        settings.refactorUpdateLimit,
        settings.pivotLimit,
        settings.workLimit,
        settings.trackDegeneracy,
        factory = context.engineFactory,
    )
}

private fun validateForReplay(capture: LpCapture) {
    capture.validateFormat()
    val settings = capture.settings
    if (settings.solverKind == LpReplaySolverKind.GENERAL) {
        require(settings.pivotLimit == 0 && settings.workLimit == 0L) {
            "general solver factory does not expose pivot/work budgets"
        }
        require(settings.refactorUpdateLimit == DEFAULT_REFACTOR_UPDATE_LIMIT && !settings.trackDegeneracy) {
            "general solver factory does not expose refactor/degeneracy settings"
        }
    }
    if (settings.solverKind == LpReplaySolverKind.TABLEAU) {
        require(settings.refactorUpdateLimit == DEFAULT_REFACTOR_UPDATE_LIMIT) {
            "tableau solver factory does not expose a refactor update limit"
        }
    }
    if (settings.solverKind != LpReplaySolverKind.GENERAL) {
        require(!settings.componentSplit) { "tableau and persistent replay cannot component-split" }
    }
    capture.events.forEachIndexed { index, event ->
        when (event) {
            is LpReplayEvent.Solve, is LpReplayEvent.SolvePrimal -> Unit

            is LpReplayEvent.Rebind -> {
                require(settings.solverKind == LpReplaySolverKind.PERSISTENT) {
                    "rebind event $index requires a persistent solver"
                }
                require(capture.model.doubleView == null) {
                    "rebind event $index cannot replay a continuous model through the current rebind seam"
                }
                require(capture.model.rowStrict.none { it }) {
                    "rebind event $index cannot replay strict rows through the current rebind seam"
                }
            }

            is LpReplayEvent.ResolveBounds -> require(settings.solverKind == LpReplaySolverKind.PERSISTENT) {
                "resolve-bounds event $index requires a persistent solver"
            }

            is LpReplayEvent.ResolveGated -> {
                require(settings.solverKind == LpReplaySolverKind.PERSISTENT) {
                    "resolve-gated event $index requires a persistent solver"
                }
                require(
                    capture.model.cost.all { it == 0L } &&
                        capture.model.doubleView?.costBits?.all { Double.fromBits(it) == 0.0 } != false,
                ) {
                    "resolve-gated event $index requires an all-zero objective"
                }
            }

            is LpReplayEvent.BoundWrite -> unsupported(index, "bound writes")

            is LpReplayEvent.Push -> unsupported(index, "push")

            is LpReplayEvent.Pop -> unsupported(index, "pop")

            is LpReplayEvent.RowActivation -> unsupported(index, "row activation")

            is LpReplayEvent.RowsAppended -> unsupported(index, "row append")

            is LpReplayEvent.ObjectiveSwap -> unsupported(index, "objective swap")

            is LpReplayEvent.Epoch -> unsupported(index, "epoch")
        }
    }
}

private fun unsupported(index: Int, operation: String): Nothing =
    error("LP replay event $index ($operation) has no production engine seam")

private class PollBudgetCancellation(private val pollLimit: Int) : Cancellation {
    private var polls = 0
    var cancelled: Boolean = false
        private set

    override fun isCancelled(): Boolean {
        if (pollLimit > 0 && ++polls >= pollLimit) cancelled = true
        return cancelled
    }
}

private class ReplayContinuationBudget(var remaining: ExactContinuationLimits) {
    val cache = LpExactContinuationCache()

    // Target admission, identity admission and execution each receive a separately capped envelope.
    fun limits(): ExactContinuationLimits = remaining.copy(
        maxWork = remaining.maxWork / 3L,
        maxAllocation = remaining.maxAllocation / 3L,
        maxTimeNs = remaining.maxTimeNs / 3L,
    )

    fun charge(metrics: ExactContinuationMetrics) {
        remaining = remaining.copy(
            maxWork = (remaining.maxWork - metrics.work).coerceAtLeast(0L),
            maxAllocation = (remaining.maxAllocation - metrics.allocation).coerceAtLeast(0L),
            maxTimeNs = (remaining.maxTimeNs - metrics.elapsedNs).coerceAtLeast(0L),
            maxPivots = (remaining.maxPivots - metrics.pivots).coerceAtLeast(0),
            maxImportPivots = (remaining.maxImportPivots - metrics.imports).coerceAtLeast(0),
        )
    }
}

private class ReplayObserver(private val budget: ReplayContinuationBudget) : LpCertificationObserver {
    var continuation: ExactContinuationMetrics? = null
        private set

    override fun observeContinuation(metrics: ExactContinuationMetrics) {
        budget.charge(metrics)
        continuation = metrics
    }

    private val attempts = IntArray(LpCertifier.entries.size)
    private val successes = IntArray(LpCertifier.entries.size)
    var exactInputAttempts: Int = 0
        private set
    var exactInputAccepted: Int = 0
        private set

    override fun observe(certifier: LpCertifier, success: Boolean) {
        attempts[certifier.ordinal]++
        if (success) successes[certifier.ordinal]++
    }

    override fun observeExactInput(accepted: Boolean) {
        exactInputAttempts++
        if (accepted) exactInputAccepted++
    }

    override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit

    fun counts(): List<LpReplayCertificationCount> = LpCertifier.entries.mapNotNull {
        val count = attempts[it.ordinal]
        if (count == 0) null else LpReplayCertificationCount(it, count, successes[it.ordinal])
    }
}

private fun DoubleArray.toRawBitsArray(): LongArray = LongArray(size) { this[it].toRawBits() }

internal class LpExactReplayStep(
    val eventIndex: Int,
    val state: LpExactState,
    val accepted: Boolean,
    val result: CertifiedLpResult?,
    val metrics: LpSolveMetrics,
    val cancelled: Boolean = false,
)

internal class LpExactReplayReport(
    val label: String,
    val seed: Long,
    val steps: List<LpExactReplayStep>,
    val declinedEventIndex: Int? = null,
    val rowMetrics: LpScopedMetrics? = null,
)

internal object LpExactReplay {
    fun replay(capture: LpExactCapture, context: LpSolveContext = LpSolveContext.Production): LpExactReplayReport {
        preflight(capture)
        val settings = capture.settings
        val cancellation = PollBudgetCancellation(settings.cancellationPollLimit)
        val steps = ArrayList<LpExactReplayStep>()
        val solver = LpScopedSolver(
            capture.initialState,
            cancellation,
            context,
            settings.refactorUpdateLimit,
            settings.pivotLimit,
            settings.workLimit,
            settings.trackDegeneracy,
            capture.maxRetainedRows,
        )
        var declined: Int? = null
        solver.use {
            if (!solver.prepare()) {
                declined = -1
            } else {
                for ((index, event) in capture.events.withIndex()) {
                    val result = if (event is LpExactReplayEvent.Solve) solver.solve(event.warm) else null
                    val accepted = if (event is LpExactReplayEvent.Solve) result != null else solver.applyEdit(event)
                    steps += LpExactReplayStep(
                        index,
                        solver.state,
                        accepted,
                        result,
                        if (event is LpExactReplayEvent.Solve) solver.lastMetrics else LpSolveMetrics(),
                        cancellation.cancelled,
                    )
                    if (!accepted) {
                        declined = index
                        break
                    }
                }
            }
        }
        return LpExactReplayReport(settings.label, settings.seed, steps, declined, solver.metrics)
    }

    private fun preflight(capture: LpExactCapture) {
        capture.validateFormat()
        require(capture.settings.solverKind == LpReplaySolverKind.PERSISTENT && !capture.settings.componentSplit) {
            "exact replay requires the persistent solver without component splitting"
        }
        val trail = LpBoundTrail(capture.initialState)
        requireNotNull(trail.state.toWorkingModel()) { "initial exact replay model has no supported projection" }
        capture.events.forEachIndexed { index, event ->
            val accepted = when (event) {
                is LpExactReplayEvent.Push -> trail.push()

                is LpExactReplayEvent.Assert -> trail.assertBound(event.column, event.upper, event.side, event.witness)

                is LpExactReplayEvent.Pop -> trail.pop(event.targetDepth)

                is LpExactReplayEvent.Objective -> trail.replaceObjective(event.objective)

                is LpExactReplayEvent.Recenter -> trail.recenter(event.origins)

                is LpExactReplayEvent.Append ->
                    trail.state.model.m < capture.maxRetainedRows &&
                        trail.append(event.row, event.scoped)

                is LpExactReplayEvent.Deactivate -> trail.deactivate(event.id)

                is LpExactReplayEvent.Compact -> trail.compact()

                is LpExactReplayEvent.Solve -> {
                    event.warm?.let { requireValidExactWarm(it, trail.state.model) }
                    true
                }
            }
            require(accepted) { "unsupported exact replay transition at event $index" }
        }
    }
}

private fun LpScopedSolver.applyEdit(event: LpExactReplayEvent): Boolean = when (event) {
    is LpExactReplayEvent.Push -> push()
    is LpExactReplayEvent.Assert -> assertBound(event.column, event.upper, event.side, event.witness)
    is LpExactReplayEvent.Pop -> pop(event.targetDepth)
    is LpExactReplayEvent.Objective -> replaceObjective(event.objective)
    is LpExactReplayEvent.Recenter -> recenter(event.origins)
    is LpExactReplayEvent.Append -> append(event.row, event.scoped)
    is LpExactReplayEvent.Deactivate -> deactivate(event.id)
    is LpExactReplayEvent.Compact -> compact()
    is LpExactReplayEvent.Solve -> false
}

private fun requireValidExactWarm(basis: Basis, model: ExactLpModel) {
    val statuses = basis.status.map {
        when (it) {
            VarStatus.BASIC -> ExactLpStatus.BASIC
            VarStatus.AT_LOWER -> ExactLpStatus.AT_LOWER
            VarStatus.AT_UPPER -> ExactLpStatus.AT_UPPER
            VarStatus.FIXED -> ExactLpStatus.FIXED
            VarStatus.FREE -> ExactLpStatus.FREE
        }
    }
    require(ExactLpBasis(basis.basicVars.toList(), statuses).validFor(model)) { "invalid exact replay warm basis" }
}
