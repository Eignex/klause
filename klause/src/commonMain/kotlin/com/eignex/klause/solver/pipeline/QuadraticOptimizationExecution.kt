package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import kotlin.coroutines.cancellation.CancellationException

/** Exact quadratic source result and arithmetic diagnostics for one invocation. */
data class QuadraticOptimizationExecution(
    /** Incumbent, global proof or explicit incomplete result. */
    val result: QuadraticOptimizationResult,
    /** Polynomial work, projection and lifting counters. */
    val polynomialStats: PolynomialTheoryStats,
    /** Numerical proposal and certification work. */
    val numericalWork: Long,
    /** Cumulative numerical plus polynomial native work. */
    val nativeWork: Long,
    /** Mandatory arithmetic representation decline, when one stopped execution. */
    val resource: PolynomialTheoryResource? = null,
) {
    private var retainedPublication: QuadraticIncumbentPublication? = null
    /**
     * Latest accepted source publication. Render this incumbent before the terminal marker when present;
     * a limit or ray certificate can retain a different private witness that is not a new improvement.
     */
    val acceptedIncumbent: QuadraticIncumbentPublication? get() = retainedPublication

    internal fun retainPublication(publication: QuadraticIncumbentPublication?) {
        retainedPublication = publication
    }
}

/** Optimize an exact quadratic objective over the existing shared source request. */
fun OpenTheoryPipeline.minimize(
    request: PolynomialTheoryRequest,
    objective: QuadraticObjectiveSpec,
    params: PolynomialTheoryParams = PolynomialTheoryParams(),
    engine: FiniteEngine = FiniteEngine.MIXED,
    sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
): QuadraticOptimizationExecution = minimize(request, objective, params, engine, sense, 1)

/** Optimize a quadratic source objective with explicit parallel lanes. */
fun OpenTheoryPipeline.minimize(
    request: PolynomialTheoryRequest,
    objective: QuadraticObjectiveSpec,
    params: PolynomialTheoryParams,
    engine: FiniteEngine,
    sense: ObjectiveSense,
    cores: Int,
): QuadraticOptimizationExecution = minimize(request, objective, params, engine, sense, cores, null)

/** Observe verified exact source incumbents in their invocation publication order. */
fun OpenTheoryPipeline.minimize(
    request: PolynomialTheoryRequest,
    objective: QuadraticObjectiveSpec,
    params: PolynomialTheoryParams,
    engine: FiniteEngine,
    sense: ObjectiveSense,
    cores: Int,
    onAccepted: ((QuadraticIncumbentPublication) -> Unit)?,
): QuadraticOptimizationExecution {
    require(cores > 0)
    val publication = QuadraticPublicationObserver(onAccepted)
    return publication.execute {
        val native = QuadraticNativeScope(params)
        var result: QuadraticOptimizationResult = QuadraticOptimizationResult.Unknown(
            null, native.reason(), sense, SolveStats.EMPTY,
        )
        if (!native.stopped) {
            val model = request.resolvedModel()
            for (term in objective.terms) for (variable in listOfNotNull(term.first, term.second)) {
                require(when (variable) {
                    is QuadraticVariable.Bool -> variable.id.index < request.problem.sourceBoolCount
                    is QuadraticVariable.Int -> variable.id.index < model.source.numIntVars
                    is QuadraticVariable.Real -> variable.id.index < request.problem.sourceRealCount
                }) { "quadratic objective coordinate out of source range" }
            }
            result = try {
                QuadraticStrategyAdapters(request, model, objective, sense, params, native, engine, cores,
                    publication).execute()
            } catch (_: CancellationException) {
                QuadraticOptimizationResult.Unknown(null, native.reason(), sense, SolveStats.EMPTY)
            }
        }
        QuadraticOptimizationExecution(
            result, native.polynomialStats, native.numericalWork, native.work,
            if (result is QuadraticOptimizationResult.Unknown || result is QuadraticOptimizationResult.BestFound) {
                native.declinedResource
            } else native.resource,
        ).also { it.retainPublication(publication.latest) }
    }
}
