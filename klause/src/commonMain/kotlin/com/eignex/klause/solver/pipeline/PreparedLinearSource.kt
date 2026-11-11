package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lowering.LinearSourceView
import com.eignex.klause.lowering.lowerLinearSource
import com.eignex.klause.lp.finiteRelaxationSupported
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.TerminationReason
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
    // A row without a finite working projection has no LP authority or finite owner, so it declines at once.
    if (!view.model.finiteRowsSupported()) {
        return PreparedSourceInvocation(request,
            SourceProblemRoute.NativeFormula(null, TerminationReason.Unsupported), stop, prepared)
    }
    val sense = source.objective?.sense ?: ObjectiveSense.MINIMIZE
    // The lowered view takes the same route as any factor source; the source overlay only qualifies its claims.
    // Purely continuous rows keep the finite LP lane, whose certified tolerance owns their decimal claims.
    val route = view.model.pipelineRoute(request.providers.prepare(view.model), objective,
        sense == ObjectiveSense.MAXIMIZE, request.boundCancellation or stop, stop, onLpStats = onLpStats,
        routePureRealToTheory = false)
    if (stop()) return interrupted()
    return PreparedSourceInvocation(request, prepared.bind(route), stop, prepared)
}

private fun PreparedLinearSource.bind(route: SourceProblemRoute): SourceProblemRoute {
    fun bound(plan: PreparedSourcePlan): PreparedSourcePlan =
        plan.withLinearSource(view.source, view.sourceIntegerCoordinates, view.sourceRealCoordinates, objective)
    return when (route) {
        is SourceProblemRoute.Finite -> route.copy(preparedPlan = bound(route.preparedPlan))

        is SourceProblemRoute.OpenTheory ->
            SourceProblemRoute.OpenTheory(route.request.withPreparedPlan(bound(route.request.preparedPlan)))

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

private fun Problem.finiteRowsSupported(): Boolean = factors.all { factor ->
    when (factor) {
        is ExactLinear -> factor.finiteRelaxationSupported()
        is LinearIndicator -> factor.row.finiteRelaxationSupported()
        else -> true
    }
}
