package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.presolve.PreparedSource
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.presolve.observePreparation
import com.eignex.klause.presolve.preparationInterrupted
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import kotlin.time.TimeSource

internal fun PolynomialTheoryRequest.prepareSource(
    config: PresolveConfig,
    objective: LinearObjective?,
    preparationStop: ExecutionControl,
    containingStop: ExecutionControl,
    budget: PresolveBudget?,
    solutionSetSensitive: Boolean = false,
): PreparedSource = PresolvePipeline.prepareSource(
    resolvedModel().source, config, objective, solutionSetSensitive, preparationStop or containingStop,
    presolveBudget = budget,
)

internal fun PolynomialTheoryRequest.afterSourcePreparation(
    prepared: PreparedSource,
    cancellation: ExecutionControl,
): PolynomialTheoryRequest? {
    require(prepared.source === resolvedModel().source)
    val mark = TimeSource.Monotonic.markNow()
    val selected = if (prepared.changed) {
        val plan = prepared.budget.observePreparation(
            PresolvePreparationStage.SOURCE_ENCODER, PresolveLane.SOURCE, prepared.problem, cancellation,
        ) { token, observation ->
            prepared.problem.preparePolynomialExecutionPlan(
                projections = requireNotNull(preparedPlan.factorProjections).forEngineView(prepared.problem),
                cancellation = token,
            ).also { if (it.decline == PreparedPlanDecline.RESOURCE) observation.preparationInterrupted() }
        }
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
    verification: ExecutionControl,
): QuadraticIncumbent? {
    if (sourcePreparation?.changed != true && sourceTolerance == null) return incumbent
    return reconstructSource(incumbent.sample, params, verification)?.let { point ->
        if (point === incumbent.sample) incumbent else incumbent.copy(sample = point)
    }
}

internal fun PolynomialTheoryRequest.publishSource(
    result: QuadraticOptimizationResult,
    params: PolynomialTheoryParams,
    verification: ExecutionControl,
): QuadraticOptimizationResult {
    if (sourcePreparation == null && sourceTolerance == null) return result
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
    verification: ExecutionControl,
): Sample? {
    val limits = PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells)
    val preparation = sourcePreparation?.takeIf { it.changed }
    val lifted = if (preparation == null) sample else {
        preparation.mapping.reconstructFrom(preparation.problem, sample).takeIf {
            checkQuadraticSourceWitness(originalPlan, it, verification, limits) == SourceEvidenceCheck.Checked
        } ?: return null
    }
    val tolerance = sourceTolerance ?: return lifted
    // A widened witness may use the slack, so the float point it publishes is the one the source acceptance judges.
    val published = lifted.withNearestReals(verification, limits) ?: return null
    return published.takeIf { tolerance.accepts(it, verification) is Verification.Accepted }
}

// This point with each real coordinate published as its nearest binary64 value and no rational authority, so that an
// acceptance check reads exactly the floats a frontend prints. An algebraic interval is bisected until both endpoints
// round to one value, which the root between them then rounds to as well; the narrowed interval is kept so every
// reader rounds the coordinate the same way, and an interval still split after the step limit publishes its
// midpoint. Null when refinement stops or declines, since no float point is then known.
private fun Sample.withNearestReals(cancellation: ExecutionControl, limits: PolynomialLimits): Sample? {
    val reals = DoubleArray(numRealVars) { id -> exactReals?.get(id)?.toDouble() ?: approximateRealValue(id) }
    val algebraic = algebraicReals?.toMutableList() ?: return copy(reals = reals)
    try {
        val arithmetic = AlgebraicArithmetic(PolynomialArithmetic(cancellation, limits))
        for (id in algebraic.indices) {
            var remaining = NEAREST_REAL_REFINEMENTS
            while (true) {
                val current = algebraic[id]
                val exact = current.rational
                val lower = current.lowerBound.toDouble()
                val upper = current.upperBound.toDouble()
                if (exact != null || lower == upper || remaining-- == 0) {
                    reals[id] = exact?.toDouble() ?: (lower / 2 + upper / 2)
                    break
                }
                algebraic[id] = arithmetic.refine(current)
            }
        }
    } catch (_: PolynomialInterrupted) {
        return null
    } catch (_: PolynomialResourceLimit) {
        return null
    }
    return copy(reals = reals, algebraicReals = algebraic)
}

// Bisections that bring an isolating interval within one binary64 rounding of any normal or subnormal value.
private const val NEAREST_REAL_REFINEMENTS = 2_200

// The slack the source's polynomial encoding states, or null when the theory decides the exact source.
internal val PolynomialTheoryRequest.sourceTolerance: PolynomialTolerance?
    get() = originalPlan.execution.polynomial?.completion?.tolerance

internal fun PolynomialTheoryParams.sourceVerificationReason(verification: ExecutionControl): TerminationReason = when {
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
