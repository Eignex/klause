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
    publication: ArithmeticPublication? = null,
) = reportQuadraticOptimization(
    execution, pipeline.render, output, statistics, routingElapsedMs, completePool, publication,
)

internal fun reportQuadraticOptimization(
    execution: QuadraticOptimizationExecution,
    render: (Sample) -> String,
    output: OutputProtocol,
    statistics: Boolean,
    routingElapsedMs: Long,
    completePool: Boolean,
    publication: ArithmeticPublication? = null,
) {
    val result = execution.result
    val point = (result as? QuadraticOptimizationResult.WithIncumbent)?.incumbent
    val reason = when (result) {
        is QuadraticOptimizationResult.BestFound -> result.reason
        is QuadraticOptimizationResult.Unknown -> result.reason
        else -> null
    }
    output.onVerdictContext(VerdictContext(terminationReason = reason, completePool = completePool))
    val rendered = point?.let { render(it.sample) }
    val accepted = if (publication == null) {
        output.onQuadraticResult(rendered, result)
        true
    } else publication.quadratic(rendered, result)
    val verdict = if (!accepted) {
        if (publication != null && publication.solutions > 0L) Verdict.BEST_FOUND else Verdict.UNKNOWN
    } else when (result) {
        is QuadraticOptimizationResult.Optimal -> Verdict.OPTIMAL
        is QuadraticOptimizationResult.UnattainedLimit -> Verdict.BEST_FOUND
        is QuadraticOptimizationResult.Unbounded -> Verdict.UNBOUNDED
        is QuadraticOptimizationResult.Infeasible -> Verdict.UNSATISFIABLE
        is QuadraticOptimizationResult.BestFound -> Verdict.BEST_FOUND
        is QuadraticOptimizationResult.Unknown -> Verdict.UNKNOWN
    }
    output.onComplete(verdict)
    if (statistics) {
        val solutions = publication?.solutions ?: if (point != null) 1L else 0L
        output.onStatistics(result.stats, result.stats.run.wallMs + routingElapsedMs, solutions)
        output.onAllowanceStatistics(listOf(
            "nraWork" to execution.polynomialStats.workSpent.toString(),
            "nraProjectedPolynomials" to execution.polynomialStats.projectedPolynomials.toString(),
            "nraLiftedCells" to execution.polynomialStats.liftedCells.toString(),
            "quadraticNumericalWork" to execution.numericalWork.toString(),
            "quadraticNativeWork" to execution.nativeWork.toString(),
        ) + execution.resource?.let { listOf("quadraticResource" to it.name) }.orEmpty())
    }
}
