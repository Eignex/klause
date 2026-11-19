package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.lowering.LinearSourceView
import com.eignex.klause.lowering.lowerLinearSource
import com.eignex.klause.lp.finiteRelaxationSupported
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

internal class PreparedLinearSource(
    val view: LinearSourceView,
    val objective: LinearObjective?,
    val claimsCoverTolerance: Boolean,
    val acceptedObjectiveBound: BigFraction?,
    val independentChecks: IndependentLinearSourceChecks? = null,
    val acceptedModelExact: Boolean = false,
)

internal class IndependentLinearSourceChecks(
    val projections: PreparedFactorProjections,
    val components: List<IndependentOpenComponent>,
)

internal fun PreparedLinearSource.workingGapTolerance(): OptimalityTolerance? {
    val policy = view.source.semantics.effective.optimalityTolerance ?: return null
    val absolute = BigFraction.of(policy.absolute.numerator, policy.absolute.denominator) * view.objectiveScale
    return policy.copy(absolute = SemanticRational.of(absolute.num, absolute.den))
}

internal fun prepareLinearSource(
    request: SourceSetupRequest,
    source: LinearSourceProblem,
    stop: ExecutionControl,
    onLpStats: ((LpStats) -> Unit)?,
): SourceRouteSelection {
    if (request.objective != null || request.quadraticObjective != null) {
        throw PipelineConfigException("a linear source binds its original affine objective in the source snapshot")
    }
    fun interrupted(): SourceRouteSelection =
        SourceRouteSelection(request, SourceProblemRoute.NativeFormula(null), stop)
    if (stop()) return interrupted()
    val numerical = source.semantics.effective.feasibilityTolerance?.isExact == false
    val envelope = request.linearToleranceEnvelope && numerical
    val working = if (request.linearToleranceEnvelope) source.absoluteToleranceEnvelope(stop) ?: return interrupted()
    else source
    val lowered = lowerLinearSource(working, request.source.settings, numerical && !envelope, stop)
        ?: return interrupted()
    val view = if (!envelope) lowered else LinearSourceView(source, lowered.model, lowered.objective,
        lowered.columns, lowered.sourceIntegerCoordinates, lowered.sourceRealCoordinates, false, false,
        lowered.objectiveScale, lowered.constraintFactorCount)
    val objective = view.objective?.toLinearObjective()
    val qualification = if (numerical) source.toleranceQualification(stop) else null
    val covers = !numerical || qualification?.verify() == true
    val acceptedBound = if (covers || source.objective == null) null else qualification?.objectiveBound()
    if (stop()) return interrupted()
    val supported = view.model.finiteRowsSupported()
    val mayRefute = !numerical && request.engine != FiniteEngine.LOCAL_SEARCH && request.engine != FiniteEngine.ALNS
    val model = if (!supported && mayRefute) {
        view.model.withFactors(view.model.factors.take(view.constraintFactorCount))
    } else view.model
    val projections = if (supported || mayRefute) request.providers.prepare(model) else null
    val components = if (!supported && mayRefute) {
        requireNotNull(projections).independentOpenComponents(stop) { model.factors[it].finiteRowSupported() }
    } else emptyList()
    if (stop()) return interrupted()
    val prepared = PreparedLinearSource(view, objective, covers, acceptedBound,
        components?.takeIf { it.isNotEmpty() }?.let { IndependentLinearSourceChecks(requireNotNull(projections), it) },
        acceptedModelExact = envelope && lowered.exact)
    if (!supported) {
        return SourceRouteSelection(request,
            SourceProblemRoute.NativeFormula(null,
                if (components == null) TerminationReason.BudgetExhausted else TerminationReason.Unsupported),
            stop, prepared)
    }
    val sense = source.objective?.sense ?: ObjectiveSense.MINIMIZE
    // The lowered view takes the same route as any factor source; the source overlay only qualifies its claims.
    // Purely continuous rows keep the finite LP lane, whose certified tolerance owns their decimal claims.
    val route = view.model.pipelineRoute(requireNotNull(projections), objective,
        sense == ObjectiveSense.MAXIMIZE, request.boundCancellation or stop, stop, onLpStats = onLpStats,
        routePureRealToTheory = false)
    if (stop()) return interrupted()
    return SourceRouteSelection(request, prepared.bind(route), stop, prepared)
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

private fun Problem.finiteRowsSupported(): Boolean = factors.all { it.finiteRowSupported() }

private fun Factor.finiteRowSupported(): Boolean = when (this) {
    is ExactLinear -> finiteRelaxationSupported()
    is LinearIndicator -> row.finiteRelaxationSupported()
    else -> true
}
