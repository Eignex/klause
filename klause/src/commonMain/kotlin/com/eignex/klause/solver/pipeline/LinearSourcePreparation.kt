package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lowering.LinearSourceView
import com.eignex.klause.lowering.lowerLinearSource
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.util.Cancellation

internal class PreparedLinearSource(
    val view: LinearSourceView,
    val objective: LinearObjective?,
    val claimsCoverTolerance: Boolean,
    val acceptedObjectiveBound: BigFraction?,
)

internal fun prepareLinearSource(
    request: SourceSetupRequest,
    source: LinearSourceProblem,
    stop: Cancellation,
    onLpStats: ((LpStats) -> Unit)?,
): PreparedSourceInvocation {
    if (request.objective != null || request.quadraticObjective != null) {
        throw PipelineConfigException("a linear source binds its original affine objective in the source snapshot")
    }
    fun interrupted(): PreparedSourceInvocation =
        PreparedSourceInvocation(request, SourceProblemRoute.NativeFormula(null), stop)
    if (stop()) return interrupted()
    val numerical = source.semantics.effective.feasibilityTolerance?.isExact == false
    val view = lowerLinearSource(source, request.source.settings, numerical, stop) ?: return interrupted()
    val objective = view.objective?.toLinearObjective()
    val qualification = if (numerical) source.toleranceQualification(stop) else null
    val covers = !numerical || qualification?.verify() == true
    val acceptedBound = if (covers || source.objective == null) null else qualification?.objectiveBound()
    if (stop()) return interrupted()
    val prepared = PreparedLinearSource(view, objective, covers, acceptedBound)
    val maximize = source.objective?.sense == ObjectiveSense.MAXIMIZE
    val native = NumericalLinearRequest(view.model, objective, maximize, stop)
    val route = if (numerical && native.numericalRowsSupported(stop)) {
        native.finiteRoute(onLpStats ?: {}) ?: SourceProblemRoute.NativeFormula(
            NativeSourceExecution.Numerical(native, view.numericalPlan(objective)),
        )
    } else {
        view.model.pipelineRoute(
            projections = request.providers.prepare(view.model),
            objective = objective,
            maximize = maximize,
            boundCancellation = request.boundCancellation or stop,
            preparationCancellation = stop,
            polynomialCancellation = stop,
            onLpStats = onLpStats,
        )
    }
    if (stop()) return interrupted()
    return PreparedSourceInvocation(request, prepared.bind(route), stop, prepared)
}

private fun PreparedLinearSource.bind(route: SourceProblemRoute): SourceProblemRoute {
    fun bound(plan: PreparedSourcePlan): PreparedSourcePlan =
        plan.withLinearSource(view.source, view.sourceIntCount, view.sourceRealCount, objective)
    return when (route) {
        is SourceProblemRoute.Finite -> route.copy(preparedPlan = bound(route.preparedPlan))
        is SourceProblemRoute.OpenTheory -> {
            val original = route.request
            SourceProblemRoute.OpenTheory(OpenTheoryRequest(
                original.model, original.objective, original.maximize, original.componentPlan,
                original.presolveConfig, original.solutionSetSensitive, original.presolveCancellation,
                original.presolveBudget, bound(original.preparedPlan), original.linearContext,
            ))
        }
        is SourceProblemRoute.PolynomialTheory -> route.copy(request = PolynomialTheoryRequest(
            route.request.problem, bound(route.preparedPlan),
        ))
        is SourceProblemRoute.UnsupportedOpen -> route.copy(preparedPlan = bound(route.preparedPlan))
        is SourceProblemRoute.NativeFormula, SourceProblemRoute.Refuted -> route
    }
}

private fun LinearSourceView.numericalPlan(objective: LinearObjective?): PreparedSourcePlan {
    val complete = ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH,
        setOf(ExecutionClaimAuthority.WITNESS, ExecutionClaimAuthority.REFUTATION,
            ExecutionClaimAuthority.OBJECTIVE_BOUND, ExecutionClaimAuthority.OPTIMALITY,
            ExecutionClaimAuthority.UNBOUNDEDNESS))
    val heuristic = ComponentStrategySupport(ProcedureCompleteness.WITNESS_SEARCH,
        setOf(ExecutionClaimAuthority.WITNESS))
    return PreparedSourcePlan(
        source,
        PreparedExecution.Numerical(source, model),
        listOf(ExecutionComponentDescriptor(
            "linear-numerical", ComponentResponsibility.COMPLETION, model.factors.indices.toList(),
            (0 until sourceIntCount).toList(), (0 until model.numRealVars).toList(),
            execution = ComponentExecutionContract(complete, heuristic),
        )),
        0, sourceIntCount, sourceRealCount,
        operations = setOf(ExecutionOperation.SATISFY, ExecutionOperation.OPTIMIZE),
    ).withLinearSource(source, sourceIntCount, sourceRealCount, objective)
}
