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

// Presolve reduces its source exactly, so a source the polynomial encoding widens by its slack enters the theory
// unreduced: an exact reduction would refute or tighten what the slack admits.
internal fun PolynomialTheoryRequest.prepareSource(
    config: PresolveConfig,
    objective: LinearObjective?,
    preparationStop: ExecutionControl,
    containingStop: ExecutionControl,
    budget: PresolveBudget?,
    solutionSetSensitive: Boolean = false,
): PreparedSource = if (resolvedModel().tolerance != null) {
    PreparedSource.unchanged(resolvedModel().source, budget)
} else PresolvePipeline.prepareSource(
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
    // A rational point is judged by the source acceptance. An algebraic one is judged by the widened formula, whose
    // region lies inside that acceptance relation by relation.
    val rationals = lifted.exactReals ?: lifted.algebraicReals?.map { it.rational }
    val accepted = if (rationals != null && rationals.none { it == null }) {
        val exact = rationals.map { checkNotNull(it) }
        val point = lifted.copy(reals = DoubleArray(exact.size) { exact[it].toDouble() }, exactReals = exact)
        tolerance.accepts(point, verification) is Verification.Accepted
    } else {
        checkQuadraticSourceWitness(originalPlan, lifted, verification, limits) == SourceEvidenceCheck.Checked
    }
    return if (accepted) lifted.withNearestReals(verification, limits) else null
}

// This point with each algebraic real coordinate published as its nearest binary64 value. The isolating interval is
// bisected until both endpoints round to one value, which the root between them then rounds to as well, and the
// narrowed interval is kept so that every reader of the exact coordinate rounds it the same way. A stopped or
// declined refinement keeps the captured approximation, since the exact coordinate remains the witness.
private fun Sample.withNearestReals(cancellation: ExecutionControl, limits: PolynomialLimits): Sample {
    val algebraic = algebraicReals?.toMutableList() ?: return this
    if (algebraic.all { it.rational != null }) return this
    val reals = DoubleArray(numRealVars) { approximateRealValue(it) }
    try {
        val arithmetic = AlgebraicArithmetic(PolynomialArithmetic(cancellation, limits))
        for (id in algebraic.indices) {
            var remaining = NEAREST_REAL_REFINEMENTS
            while (remaining-- > 0) {
                val current = algebraic[id]
                val exact = current.rational
                if (exact != null) {
                    reals[id] = exact.toDouble()
                    break
                }
                val lower = current.lowerBound.toDouble()
                if (lower == current.upperBound.toDouble()) {
                    reals[id] = lower
                    break
                }
                algebraic[id] = arithmetic.refine(current)
            }
        }
    } catch (_: PolynomialInterrupted) {
        return this
    } catch (_: PolynomialResourceLimit) {
        return this
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
