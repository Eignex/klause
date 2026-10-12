package com.eignex.klause.cli

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.PolynomialTheoryRequest
import com.eignex.klause.solver.pipeline.NonlinearIntegerTheoryRequest
import com.eignex.klause.solver.pipeline.QuadraticOptimizationExecution
import com.eignex.klause.solver.result.QuadraticOptimizationResult

internal sealed interface QuadraticRequest {
    data class Polynomial(val request: PolynomialTheoryRequest) : QuadraticRequest
    data class Integer(val request: NonlinearIntegerTheoryRequest) : QuadraticRequest
}

internal fun quadraticSolvable(
    request: QuadraticRequest,
    objective: QuadraticObjectiveSpec,
    sense: ObjectiveSense,
    render: (Sample) -> String,
    admissionElapsedMs: Long,
): Solvable = Solvable(
    finite = null,
    render = render,
    objectiveValue = null,
    pipeline = SolvablePipeline.QuadraticTheory(request, objective, sense, render),
    routingElapsedMs = admissionElapsedMs,
)

internal fun reportQuadraticOptimization(
    execution: QuadraticOptimizationExecution,
    pipeline: SolvablePipeline.QuadraticTheory,
    output: OutputProtocol,
    statistics: Boolean,
    routingElapsedMs: Long,
    completePool: Boolean,
) = reportQuadraticOptimization(execution, pipeline.render, output, statistics, routingElapsedMs, completePool)

internal fun reportQuadraticOptimization(
    execution: QuadraticOptimizationExecution,
    render: (Sample) -> String,
    output: OutputProtocol,
    statistics: Boolean,
    routingElapsedMs: Long,
    completePool: Boolean,
) {
    val result = execution.result
    val point = (result as? QuadraticOptimizationResult.WithIncumbent)?.incumbent
    val reason = when (result) {
        is QuadraticOptimizationResult.BestFound -> result.reason
        is QuadraticOptimizationResult.Unknown -> result.reason
        else -> null
    }
    output.onVerdictContext(VerdictContext(terminationReason = reason, completePool = completePool))
    output.onQuadraticResult(point?.let { render(it.sample) }, result)
    val verdict = when (result) {
        is QuadraticOptimizationResult.Optimal -> Verdict.OPTIMAL
        is QuadraticOptimizationResult.UnattainedLimit -> Verdict.SATISFIABLE
        is QuadraticOptimizationResult.Unbounded -> Verdict.UNBOUNDED
        is QuadraticOptimizationResult.Infeasible -> Verdict.UNSATISFIABLE
        is QuadraticOptimizationResult.BestFound -> Verdict.BEST_FOUND
        is QuadraticOptimizationResult.Unknown -> Verdict.UNKNOWN
    }
    output.onComplete(verdict)
    if (statistics) {
        output.onStatistics(result.stats, result.stats.run.wallMs + routingElapsedMs, if (point != null) 1L else 0L)
        output.onAllowanceStatistics(listOf(
            "nraWork" to execution.polynomialStats.workSpent.toString(),
            "nraProjectedPolynomials" to execution.polynomialStats.projectedPolynomials.toString(),
            "nraLiftedCells" to execution.polynomialStats.liftedCells.toString(),
            "quadraticNumericalWork" to execution.numericalWork.toString(),
            "quadraticNativeWork" to execution.nativeWork.toString(),
        ) + execution.resource?.let { listOf("quadraticResource" to it.name) }.orEmpty())
    }
}
