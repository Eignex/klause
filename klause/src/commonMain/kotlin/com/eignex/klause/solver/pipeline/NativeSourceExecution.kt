package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.hasFiniteIntegerRanges
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

internal fun prepareFactorQuadratic(
    request: SourceSetupRequest,
    projections: PreparedFactorProjections,
    polynomialStop: Cancellation,
    stop: Cancellation,
): SourceProblemRoute {
    val source = request.source.problem
    val bounded = if (source.hasFiniteIntegerRanges()) {
        source
    } else {
        when (val result = source.closeOpenBounds(request.boundCancellation or stop)) {
            OpenPresolveResult.Refuted -> return SourceProblemRoute.Refuted
            is OpenPresolveResult.Tightened -> result.spec
        }
    }
    val plan = bounded.preparePolynomialExecutionPlan(
        original = source,
        providers = request.providers,
        projections = projections.forEngineView(bounded),
        cancellation = polynomialStop,
    ).withObjective(
        requireNotNull(request.quadraticObjective),
        if (request.maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE,
    )
    if (stop()) return SourceProblemRoute.NativeFormula(null)
    if (plan.execution !is PreparedExecution.Polynomial) {
        return SourceProblemRoute.NativeFormula(
            null,
            if (plan.decline == PreparedPlanDecline.RESOURCE) {
                TerminationReason.BudgetExhausted
            } else {
                TerminationReason.Unsupported
            },
        )
    }
    return SourceProblemRoute.NativeFormula(NativeSourceExecution.Polynomial(plan.polynomialRequest()))
}

internal fun executeNativeSource(
    request: SourceExecutionRequest,
    route: SourceProblemRoute.NativeFormula,
    callbacks: SourceExecutionCallbacks,
): SourceExecutionResult {
    val invocation = request.invocation
    val native = route.execution
    if (native == null) {
        val reason = if (invocation.nativeDeadline()()) {
            TerminationReason.Timeout
        } else {
            requireNotNull(route.preparationReason)
        }
        return SourceExecutionResult.Declined(invocation, reason)
    }
    native.preparedPlan.decline?.let { decline ->
        return SourceExecutionResult.Declined(
            invocation,
            when {
                invocation.nativeDeadline()() -> TerminationReason.Timeout
                invocation.cancellation() -> TerminationReason.Cancelled
                decline == PreparedPlanDecline.RESOURCE -> TerminationReason.BudgetExhausted
                else -> TerminationReason.Unsupported
            },
        )
    }
    val engine = invocation.request.engine
    val sense = if (invocation.maximizeSourceObjective) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE
    val quadratic = invocation.sourceQuadraticObjective
    val affine = invocation.sourceObjective
    return when (native) {
        is NativeSourceExecution.Numerical -> {
            val publication = SourcePublicationObserver(callbacks.onLinearIncumbent)
            val controls = request.theory.copy(
                cancellation = request.theory.cancellation or invocation.cancellation,
                timeout = request.theory.timeout or invocation.nativeDeadline(),
            )
            SourceExecutionResult.Numerical(
                invocation,
                publication.execute {
                native.request.solve(
                    controls,
                    complete = engine != FiniteEngine.LOCAL_SEARCH && engine != FiniteEngine.ALNS,
                    onIncumbent = { sample, value ->
                        publication.accepted(sample, com.eignex.klause.solver.evidence.SourceValue.Rational(value))
                    },
                )
            }
            )
        }

        is NativeSourceExecution.Polynomial -> {
            val params = request.polynomial.nativeBoundTo(invocation)
            when {
                quadratic != null -> SourceExecutionResult.Quadratic(
                    invocation,
                    OpenTheoryPipeline.minimize(
                        native.request,
                        quadratic,
                        params,
                        engine,
                        sense,
                        request.cores,
                        callbacks.onQuadraticIncumbent,
                    ),
                )

                affine != null -> SourceExecutionResult.Quadratic(
                    invocation,
                    OpenTheoryPipeline.optimize(
                        native.request,
                        affine,
                        params,
                        engine,
                        sense,
                        request.cores,
                        callbacks.onQuadraticIncumbent,
                    ),
                )

                else -> SourceExecutionResult.Polynomial(
                    invocation,
                    OpenTheoryPipeline.execute(native.request, params, engine, request.cores),
                )
            }
        }

        is NativeSourceExecution.IntegerPolynomial -> {
            val controls = request.nonlinearInteger ?: NonlinearIntegerTheoryParams(polynomial = request.polynomial)
            val params = controls.copy(polynomial = controls.polynomial.nativeBoundTo(invocation))
            val objective = quadratic ?: affine?.toQuadraticObjectiveSpec()
            if (objective == null) {
                SourceExecutionResult.Polynomial(
                    invocation,
                    OpenTheoryPipeline.execute(native.request, params, engine, request.cores),
                )
            } else {
                SourceExecutionResult.Quadratic(
                    invocation,
                    OpenTheoryPipeline.minimize(
                        native.request,
                        objective,
                        params,
                        engine,
                        sense,
                        request.cores,
                        callbacks.onQuadraticIncumbent,
                    ),
                )
            }
        }

        is NativeSourceExecution.Functions -> {
            val controls = request.transcendental ?: TranscendentalTheoryParams(
                maximumWork = request.polynomial.maximumWork,
                maximumDenseCoefficients = request.polynomial.maximumDenseCoefficients,
                maximumMatrixCells = request.polynomial.maximumMatrixCells,
                timeout = request.polynomial.timeout,
                cancellation = request.polynomial.cancellation,
                strategy = request.polynomial.strategy,
            )
            val params = controls.copy(
                cancellation = controls.cancellation or invocation.cancellation,
                timeout = controls.timeout or invocation.nativeDeadline(),
            )
            SourceExecutionResult.Transcendental(
                invocation,
                OpenTheoryPipeline.execute(native.request, params, engine, request.cores),
            )
        }
    }
}

private fun PolynomialTheoryParams.nativeBoundTo(invocation: PreparedSourceInvocation): PolynomialTheoryParams = copy(
    cancellation = cancellation or invocation.cancellation,
    timeout = timeout or invocation.nativeDeadline(),
)

private fun PreparedSourceInvocation.nativeDeadline(): Cancellation =
    cancellation.deadline()?.let { Cancellation.until(it) } ?: Cancellation.Never
