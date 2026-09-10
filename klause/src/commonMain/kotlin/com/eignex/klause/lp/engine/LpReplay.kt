package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal enum class LpReplayOperation { SOLVE, SOLVE_PRIMAL, REBIND, RESOLVE_BOUNDS, RESOLVE_GATED }

internal enum class LpCandidateKind { NONE, FLOAT_OPTIMUM, FLOAT_BOUND, FLOAT_INFEASIBILITY }

internal enum class LpIndependentValidation { VALIDATED, DECLINED, REFUTED }

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
    fun replay(capture: LpCapture, validator: LpReplayValidator? = null): LpReplayReport {
        validateForReplay(capture)
        var model = capture.model.toModel()
        val settings = capture.settings
        var cancellation: Cancellation = PollBudgetCancellation(settings.cancellationPollLimit)
        val solver = newSolver(model, settings, cancellation)
        val steps = ArrayList<LpReplayStep>(capture.events.size)
        solver.use {
            capture.events.forEachIndexed { index, event ->
                val step = when (event) {
                    is LpReplayEvent.Solve -> replaySolve(
                        index,
                        LpReplayOperation.SOLVE,
                        model,
                        solver,
                        cancellation,
                    ) { solver.solve(event.warm?.toBasis(model.hasUpper, model.m)) }

                    is LpReplayEvent.SolvePrimal -> replaySolve(
                        index,
                        LpReplayOperation.SOLVE_PRIMAL,
                        model,
                        solver,
                        cancellation,
                    ) { solver.solvePrimal(event.warm?.toBasis(model.hasUpper, model.m)) }

                    is LpReplayEvent.Rebind -> {
                        val next = model.rebind(event.lo, event.hi)
                        if (event.cancellationPollLimit != null) {
                            cancellation = PollBudgetCancellation(event.cancellationPollLimit)
                        }
                        check((solver as PersistentLpSolver).rebind(next, cancellation)) {
                            "persistent solver rejected replay rebind at event $index"
                        }
                        model = next
                        emptyStep(index, LpReplayOperation.REBIND)
                    }

                    is LpReplayEvent.ResolveBounds -> replaySolve(
                        index,
                        LpReplayOperation.RESOLVE_BOUNDS,
                        model,
                        solver,
                        cancellation,
                    ) { (solver as PersistentLpSolver).resolveBounds() }

                    is LpReplayEvent.ResolveGated -> replayUncertified(
                        index,
                        LpReplayOperation.RESOLVE_GATED,
                        solver,
                        event.enforced,
                    ) { (solver as PersistentLpSolver).resolveGated(event.enforced.copyOf()) }

                    else -> error("unsupported event passed replay preflight: ${event::class.simpleName}")
                }
                step.independentCheck = validator?.validate(model, step) ?: step.independentCheck
                steps += step
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
        solve: () -> FloatLpResult?,
    ): LpReplayStep {
        val observer = ReplayObserver()
        val result = solve()
        val metrics = solver.lastMetrics
        val certified = certifyLpResult(model, solver, result, cancellation, observer)
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
}

private fun newSolver(model: LpModel, settings: LpReplaySettings, cancellation: Cancellation): LpSolver =
    when (settings.solverKind) {
        LpReplaySolverKind.GENERAL -> newLpSolver(model, cancellation, settings.componentSplit)

        LpReplaySolverKind.TABLEAU -> newTableauCutSolver(
            model,
            cancellation,
            settings.pivotLimit,
            settings.workLimit,
            settings.trackDegeneracy,
        )

        LpReplaySolverKind.PERSISTENT -> newPersistentLpSolver(
            model,
            cancellation,
            settings.refactorUpdateLimit,
            settings.pivotLimit,
            settings.workLimit,
            settings.trackDegeneracy,
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

private class ReplayObserver : LpCertificationObserver {
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
)

internal object LpExactReplay {
    fun replay(capture: LpExactCapture): LpExactReplayReport {
        val states = preflight(capture)
        val settings = capture.settings
        val cancellation = PollBudgetCancellation(settings.cancellationPollLimit)
        var state = states.first()
        var model = requireNotNull(state.toWorkingModel())
        val steps = ArrayList<LpExactReplayStep>()
        newPersistentLpSolver(
            model,
            cancellation,
            settings.refactorUpdateLimit,
            settings.pivotLimit,
            settings.workLimit,
            settings.trackDegeneracy,
        ).use { solver ->
            if (!solver.adopt(state, cancellation)) {
                return LpExactReplayReport(settings.label, settings.seed, steps, -1)
            }
            var solved = false
            capture.events.forEachIndexed { index, event ->
                val next = states[index + 1]
                if (!solver.adopt(next, cancellation)) {
                    steps += LpExactReplayStep(index, state, false, null, LpSolveMetrics(), cancellation.cancelled)
                    return LpExactReplayReport(settings.label, settings.seed, steps, index)
                }
                state = next
                model = requireNotNull(state.toWorkingModel())
                if (event is LpExactReplayEvent.Solve) {
                    val warm = event.warm
                    val result = if (!solved || warm != null) solver.solve(warm) else solver.resolveBounds()
                    solved = true
                    val metrics = solver.lastMetrics
                    if (cancellation.cancelled) {
                        steps += LpExactReplayStep(index, state, false, null, metrics, cancelled = true)
                        return LpExactReplayReport(settings.label, settings.seed, steps, index)
                    }
                    val certified = certifyLpResult(model, solver, result, cancellation)
                    if (cancellation.cancelled) {
                        steps += LpExactReplayStep(index, state, false, null, metrics, cancelled = true)
                        return LpExactReplayReport(settings.label, settings.seed, steps, index)
                    }
                    steps += LpExactReplayStep(index, state, true, certified, metrics)
                } else {
                    steps += LpExactReplayStep(index, state, true, null, LpSolveMetrics())
                }
            }
        }
        return LpExactReplayReport(settings.label, settings.seed, steps)
    }

    private fun preflight(capture: LpExactCapture): List<LpExactState> {
        capture.validateFormat()
        require(capture.settings.solverKind == LpReplaySolverKind.PERSISTENT && !capture.settings.componentSplit) {
            "exact replay requires the persistent solver without component splitting"
        }
        val trail = LpBoundTrail(capture.model)
        val states = ArrayList<LpExactState>(capture.events.size + 1)
        requireNotNull(trail.state.toWorkingModel()) { "initial exact replay model has no supported projection" }
        states += trail.state
        capture.events.forEachIndexed { index, event ->
            val accepted = when (event) {
                is LpExactReplayEvent.Push -> trail.push(Cancellation.Never)

                is LpExactReplayEvent.Assert ->
                    trail.assertBound(event.column, event.upper, event.side, event.witness, Cancellation.Never)

                is LpExactReplayEvent.Pop -> trail.pop(event.targetDepth, Cancellation.Never)

                is LpExactReplayEvent.Objective -> trail.replaceObjective(event.objective, Cancellation.Never)

                is LpExactReplayEvent.Recenter -> trail.recenter(event.origins, Cancellation.Never)

                is LpExactReplayEvent.Solve -> {
                    event.warm?.let { requireValidExactWarm(it, trail.state.model) }
                    true
                }
            }
            require(accepted) { "unsupported exact replay transition at event $index" }
            requireNotNull(trail.state.toWorkingModel()) { "unsupported exact replay projection at event $index" }
            states += trail.state
        }
        return states
    }
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
