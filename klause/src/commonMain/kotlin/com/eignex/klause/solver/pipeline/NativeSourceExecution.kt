package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.hasFiniteIntegerRanges
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.objective.LinearObjective
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
            // A typed quadratic result requires its producing engine's completion certificate.
            OpenPresolveResult.Refuted -> if (request.requiresQuadraticCompletion) {
                source
            } else {
                return SourceProblemRoute.Refuted
            }

            is OpenPresolveResult.Tightened -> result.spec
        }
    }
    val plan = bounded.preparePolynomialExecutionPlan(
        original = source,
        providers = request.providers,
        projections = projections.forEngineView(bounded),
        cancellation = polynomialStop,
    ).let { prepared ->
        request.quadraticObjective?.let {
            prepared.withObjective(it, if (request.maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE)
        } ?: prepared.withObjective(requireNotNull(request.objective), request.maximize)
    }
    if (stop()) return SourceProblemRoute.NativeFormula(null)
    val native = when (val execution = plan.execution) {
        is PreparedExecution.Polynomial -> NativeSourceExecution.Polynomial(plan.polynomialRequest())

        is PreparedExecution.NonlinearInteger -> NativeSourceExecution.IntegerPolynomial(
            NonlinearIntegerTheoryRequest(execution.source, plan),
        )

        else -> null
    }
    if (native == null) {
        return SourceProblemRoute.NativeFormula(
            null,
            if (plan.decline == PreparedPlanDecline.RESOURCE) {
                TerminationReason.BudgetExhausted
            } else {
                TerminationReason.Unsupported
            },
        )
    }
    return SourceProblemRoute.NativeFormula(native)
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
                    native.request.solve(controls, engine, request.cores, native.preparedPlan, publication)
                },
            )
        }

        is NativeSourceExecution.Polynomial -> {
            val params = request.polynomial.nativeBoundTo(invocation)
            val source = if (invocation.canonicalPolynomial) {
                val model = native.request.resolvedModel().source
                val protection = if (quadratic == null) {
                    affine?.let { if (invocation.maximizeSourceObjective) it.negated() else it }
                } else {
                    // A nonlinear objective has no coefficient sign for dual fixing.
                    LinearObjective(boolWeights = LongArray(model.numBoolVars) { 1L },
                        intCoefficients = LongArray(model.numIntVars) { 1L })
                }
                native.request.prepareSource(request.presolveConfig ?: PresolveConfig.DEFAULT, protection,
                    request.presolveCancellation, params.cancellation or params.timeout, request.presolveBudget,
                    solutionSetSensitive = quadratic != null)
            } else null
            if (source?.infeasible == true) return SourceExecutionResult.Refuted(invocation).also {
                it.preparationStats = source.stats
            }
            val prepared = if (source == null) native.request else {
                native.request.afterSourcePreparation(source, params.cancellation or params.timeout)
                    ?: return SourceExecutionResult.Declined(invocation, TerminationReason.BudgetExhausted).also {
                        it.preparationStats = source.stats
                    }
            }
            val result = when {
                quadratic != null -> SourceExecutionResult.Quadratic(
                    invocation,
                    OpenTheoryPipeline.minimize(
                        prepared,
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
                        prepared,
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
                    OpenTheoryPipeline.execute(prepared, params, engine, request.cores),
                )
            }
            result.also { it.preparationStats = source?.stats }
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
