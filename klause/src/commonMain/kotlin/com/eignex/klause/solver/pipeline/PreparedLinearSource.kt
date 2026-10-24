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
    val companions = request.linearTraversal == LinearTraversal.NUMERICAL_COMPANIONS ||
        request.linearTraversal == LinearTraversal.NUMERICAL_COMPANIONS_INDEPENDENT
    val native = NumericalLinearRequest(view.model, objective, maximize, stop,
        boundOrdered = request.linearTraversal == LinearTraversal.NUMERICAL_FRONTIER || companions)
    val route = if (companions) {
        val finite = native.finiteRoute(request.boundCancellation or stop, onLpStats ?: {})
        val companion = (finite as? SourceProblemRoute.Finite)?.problem?.takeUnless {
            it.requiresExactObjectiveRoute(objective, stop)
        }
        SourceProblemRoute.NativeFormula(NativeSourceExecution.Numerical(
            native.withCompanions(companion,
                sharing = request.linearTraversal == LinearTraversal.NUMERICAL_COMPANIONS),
            view.numericalPlan(objective)))
    } else if (request.linearTraversal == LinearTraversal.AUTOMATIC && native.numericalRowsSupported(stop)) {
        val finite = native.finiteRoute(request.boundCancellation or stop, onLpStats ?: {})
        if (finite is SourceProblemRoute.Finite && finite.problem.requiresExactObjectiveRoute(objective, stop)) {
            SourceProblemRoute.NativeFormula(NativeSourceExecution.Numerical(native, view.numericalPlan(objective)))
        } else {
            finite ?: SourceProblemRoute.NativeFormula(
            NativeSourceExecution.Numerical(native, view.numericalPlan(objective)),
        )
        }
    } else {
        SourceProblemRoute.NativeFormula(
            NativeSourceExecution.Numerical(native, view.numericalPlan(objective)),
        )
    }
    if (stop()) return interrupted()
    return PreparedSourceInvocation(request, prepared.bind(route), stop, prepared)
}

private fun PreparedLinearSource.bind(route: SourceProblemRoute): SourceProblemRoute {
    fun bound(plan: PreparedSourcePlan): PreparedSourcePlan =
        plan.withLinearSource(view.source, view.sourceIntegerCoordinates, view.sourceRealCoordinates, objective)
    return when (route) {
        is SourceProblemRoute.Finite -> route.copy(preparedPlan = bound(route.preparedPlan))

        is SourceProblemRoute.OpenTheory -> {
            val original = route.request
            SourceProblemRoute.OpenTheory(
                OpenTheoryRequest(
                    original.model, original.objective, original.maximize, original.componentPlan,
                    original.presolveConfig, original.solutionSetSensitive, original.presolveCancellation,
                    original.presolveBudget, bound(original.preparedPlan), original.linearContext,
                ),
            )
        }

        is SourceProblemRoute.PolynomialTheory -> route.copy(
            request = PolynomialTheoryRequest(
                route.request.problem,
                bound(route.preparedPlan),
            ),
        )

        is SourceProblemRoute.UnsupportedOpen -> route.copy(preparedPlan = bound(route.preparedPlan))

        is SourceProblemRoute.NativeFormula, SourceProblemRoute.Refuted -> route
    }
}

private fun LinearSourceView.numericalPlan(objective: LinearObjective?): PreparedSourcePlan {
    val complete = ComponentStrategySupport(
        ProcedureCompleteness.WITNESS_SEARCH,
        setOf(
            ExecutionClaimAuthority.WITNESS,
            ExecutionClaimAuthority.REFUTATION,
            ExecutionClaimAuthority.OBJECTIVE_BOUND,
            ExecutionClaimAuthority.OPTIMALITY,
            ExecutionClaimAuthority.UNBOUNDEDNESS,
        ),
    )
    val heuristic = ComponentStrategySupport(
        if (model.numBoolVars == 0) ProcedureCompleteness.WITNESS_SEARCH else ProcedureCompleteness.UNAVAILABLE,
        if (model.numBoolVars == 0) setOf(ExecutionClaimAuthority.WITNESS) else emptySet(),
        if (model.numBoolVars == 0) emptyList() else
            listOf(ExecutionDecline(ExecutionDeclineCause.HEURISTIC_PROJECTION, "linear-numerical")),
    )
    return PreparedSourcePlan(
        source,
        PreparedExecution.Numerical(source, model),
        listOf(
            ExecutionComponentDescriptor(
                "linear-numerical",
                ComponentResponsibility.COMPLETION,
                model.factors.indices.toList(),
                (0 until model.numIntVars).toList(),
                (0 until model.numRealVars).toList(),
                execution = ComponentExecutionContract(complete, heuristic),
            ),
        ),
        0,
        model.numIntVars,
        model.numRealVars,
        operations = setOf(ExecutionOperation.SATISFY, ExecutionOperation.OPTIMIZE),
    ).withLinearSource(source, sourceIntegerCoordinates, sourceRealCoordinates, objective)
}
