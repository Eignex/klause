package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.presolve.PreparedSource
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

internal fun PolynomialTheoryRequest.prepareSource(
    config: PresolveConfig,
    objective: LinearObjective?,
    preparationStop: Cancellation,
    containingStop: Cancellation,
    budget: PresolveBudget?,
    solutionSetSensitive: Boolean = false,
): PreparedSource = PresolvePipeline.prepareSource(
    resolvedModel().source, config, objective, solutionSetSensitive, preparationStop or containingStop,
    presolveBudget = budget,
)

internal fun PolynomialTheoryRequest.afterSourcePreparation(
    prepared: PreparedSource,
    cancellation: Cancellation,
): PolynomialTheoryRequest? {
    require(prepared.source === resolvedModel().source)
    val mark = TimeSource.Monotonic.markNow()
    val selected = if (prepared.changed) {
        val plan = prepared.problem.preparePolynomialExecutionPlan(
            projections = requireNotNull(preparedPlan.factorProjections).forEngineView(prepared.problem),
            cancellation = cancellation,
        )
        if (plan.execution !is PreparedExecution.Polynomial) {
            prepared.elapsed += mark.elapsedNow()
            return null
        }
        plan.polynomialRequest()
    } else this
    prepared.elapsed += mark.elapsedNow()
    return PolynomialTheoryRequest(selected.problem, selected.preparedPlan, prepared, preparedPlan)
}

internal fun PolynomialTheoryRequest.reconstructSource(
    incumbent: QuadraticIncumbent,
    params: PolynomialTheoryParams,
    verification: Cancellation,
): QuadraticIncumbent? {
    if (sourcePreparation?.changed != true) return incumbent
    return reconstructSource(incumbent.sample, params, verification)?.let { incumbent.copy(sample = it) }
}

internal fun PolynomialTheoryRequest.publishSource(
    result: QuadraticOptimizationResult,
    params: PolynomialTheoryParams,
    verification: Cancellation,
): QuadraticOptimizationResult {
    if (sourcePreparation == null) return result
    val stats = preparationStats(result.stats)
    val point = (result as? QuadraticOptimizationResult.WithIncumbent)?.let {
        reconstructSource(it.incumbent, params, verification)
    }
    if (result is QuadraticOptimizationResult.WithIncumbent && point == null) {
        val reason = params.sourceVerificationReason(verification)
        return QuadraticOptimizationResult.Unknown(null, reason, result.sense, stats)
    }
    return when (result) {
        is QuadraticOptimizationResult.Optimal -> result.copy(incumbent = requireNotNull(point), stats = stats)
        is QuadraticOptimizationResult.UnattainedLimit -> result.copy(incumbent = requireNotNull(point), stats = stats)
        is QuadraticOptimizationResult.Unbounded -> result.copy(incumbent = requireNotNull(point), stats = stats)
        is QuadraticOptimizationResult.BestFound -> result.copy(incumbent = requireNotNull(point), stats = stats)
        is QuadraticOptimizationResult.Infeasible -> result.copy(stats = stats)
        is QuadraticOptimizationResult.Unknown -> result.copy(stats = stats)
    }
}

internal fun PolynomialTheoryRequest.reconstructSource(
    sample: Sample,
    params: PolynomialTheoryParams,
    verification: Cancellation,
): Sample? {
    val preparation = sourcePreparation?.takeIf { it.changed } ?: return sample
    val lifted = preparation.mapping.reconstructFrom(preparation.problem, sample)
    val checked = checkQuadraticSourceWitness(originalPlan, lifted, verification,
        PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells))
    return lifted.takeIf { checked == SourceEvidenceCheck.Checked }
}

internal fun PolynomialTheoryParams.sourceVerificationReason(verification: Cancellation): TerminationReason = when {
    timeout() -> TerminationReason.Timeout
    cancellation() -> TerminationReason.Cancelled
    verification() -> TerminationReason.BudgetExhausted
    else -> TerminationReason.Unsupported
}

internal fun PolynomialTheoryRequest.preparationStats(stats: SolveStats): SolveStats {
    val preparation = sourcePreparation?.stats ?: return stats
    val elapsedMs = requireNotNull(preparation.effort).elapsed.inWholeMilliseconds
    return stats.copy(presolve = preparation, run = stats.run.copy(wallMs = stats.run.wallMs + elapsedMs))
}

internal fun SolveResult.withStats(stats: SolveStats): SolveResult = when (this) {
    is SolveResult.Sat -> copy(stats = stats)
    is SolveResult.Unsat -> copy(stats = stats)
    is SolveResult.Unknown -> copy(stats = stats)
}
