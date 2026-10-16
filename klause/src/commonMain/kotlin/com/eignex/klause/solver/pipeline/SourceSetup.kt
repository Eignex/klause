package com.eignex.klause.solver.pipeline

import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.util.Cancellation

/** One operation binding over an attached source and its containing preparation allowance. */
class SourceSetupRequest(
    /** Source identity, interpretation and neutral construction hints. */
    val source: SourceAttachment,
    /** Operation whose execution capabilities are inspected. */
    val operation: ExecutionOperation,
    /** Requested finite strategy, retained when that source route is selected. */
    val engine: FiniteEngine,
    /** Original source objective before consumer-specific direction normalization. */
    val objective: LinearObjective? = null,
    /** Original maximization direction. */
    val maximize: Boolean = false,
    /** Source projection providers captured once for this setup. */
    val providers: FactorProjectionProviders = source.providers,
    /** Containing allowance for required source preparation and native views. */
    val cancellation: Cancellation = Cancellation.Never,
    /** Optional deduction allowance, also bounded by [cancellation]. */
    val boundCancellation: Cancellation = cancellation,
) {
    init {
        require((operation == ExecutionOperation.OPTIMIZE) == (objective != null)) {
            "optimization and its source objective must be bound together"
        }
        require(objective != null || !maximize) { "maximization requires a source objective" }
    }

    internal fun finiteObjective(): LinearObjective? {
        val selected = objective ?: return null
        if (!maximize) return selected
        require(
            selected.constant != Long.MIN_VALUE &&
                selected.intCoefficientValues.none { it == Long.MIN_VALUE } &&
                selected.boolWeightValues.none { it == Long.MIN_VALUE },
        ) {
            "finite maximization objective cannot be normalized within signed-word coefficients"
        }
        return selected.negated()
    }
}

/** Captures one source route and retains its operation binding for execution. */
object SourceSetup {
    /** Prepare from neutral source data without allocating heuristic worker state. */
    fun prepare(request: SourceSetupRequest): PreparedSourceInvocation = prepareObserved(request, null)

    /** Prepare the same source binding while observing bound-probing LP work. */
    fun prepare(request: SourceSetupRequest, onLpStats: (LpStats) -> Unit): PreparedSourceInvocation =
        prepareObserved(request, onLpStats)

    internal fun prepare(request: SourceSetupRequest, polynomialCancellation: Cancellation): PreparedSourceInvocation =
        prepareObserved(request, null, polynomialCancellation)

    private fun prepareObserved(
        request: SourceSetupRequest,
        onLpStats: ((LpStats) -> Unit)?,
        polynomialCancellation: Cancellation = request.cancellation,
    ): PreparedSourceInvocation {
        val stop = request.cancellation.encodingPreparationScope()
        val problem = request.source.problem
        val projections = (problem as? BakedProblem)?.factorProjections
            ?.takeIf { it.providers === request.providers } ?: request.providers.prepare(problem)
        val route = problem.pipelineRoute(
            projections = projections,
            objective = request.objective,
            maximize = request.maximize,
            boundCancellation = request.boundCancellation,
            preparationCancellation = stop,
            polynomialCancellation = polynomialCancellation or stop,
            onLpStats = onLpStats,
        )
        return PreparedSourceInvocation(request, route, stop)
    }
}
