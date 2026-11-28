package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.RationalInterval
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
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
import com.eignex.klause.solver.result.QuadraticObjectiveBound
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.withinOptimalityGap
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
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
    return PolynomialTheoryRequest(selected.problem, selected.preparedPlan, prepared, preparedPlan, gapTolerance)
}

internal fun PolynomialTheoryRequest.reconstructSource(
    incumbent: QuadraticIncumbent,
    objective: QuadraticObjectiveSpec,
    params: PolynomialTheoryParams,
    verification: ExecutionControl,
): QuadraticIncumbent? {
    if (sourcePreparation?.changed != true && sourceTolerance == null) return incumbent
    return reconstructSource(incumbent.sample, params, verification)?.let { point ->
        when {
            point === incumbent.sample -> incumbent
            // A widened point is published as its floats, so the value it reports is the objective of those floats.
            sourceTolerance != null -> QuadraticIncumbent(point, rationalScalar(objective.publishedValue(point)))
            else -> incumbent.copy(sample = point)
        }
    }
}

internal fun PolynomialTheoryRequest.publishSource(
    result: QuadraticOptimizationResult,
    objective: QuadraticObjectiveSpec,
    params: PolynomialTheoryParams,
    verification: ExecutionControl,
): QuadraticOptimizationResult {
    if (sourcePreparation == null && sourceTolerance == null) return result
    val stats = preparationStats(result.stats)
    val point = (result as? QuadraticOptimizationResult.WithIncumbent)?.let {
        reconstructSource(it.incumbent, objective, params, verification)
    }
    if (result is QuadraticOptimizationResult.WithIncumbent && point == null) {
        val reason = params.sourceVerificationReason(verification)
        return QuadraticOptimizationResult.Unknown((result as? QuadraticOptimizationResult.Optimal)?.bound, reason,
            result.sense, stats)
    }
    if (result is QuadraticOptimizationResult.Optimal && sourceTolerance != null &&
        !publishesOptimum(objective, requireNotNull(point), result.bound)
    ) {
        return QuadraticOptimizationResult.BestFound(point, result.bound, TerminationReason.RepresentationDeclined,
            result.sense, stats)
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
    val published = lifted.publishedFloats(verification, limits) ?: return null
    return published.takeIf { tolerance.accepts(it, verification) is Verification.Accepted }
}

private fun QuadraticObjectiveSpec.publishedTerms(point: Sample): List<BigFraction> {
    fun value(variable: QuadraticVariable): BigFraction = when (variable) {
        is QuadraticVariable.Bool -> if (point.boolValue(variable.id.index)) BigFraction.ONE else BigFraction.ZERO
        is QuadraticVariable.Int -> BigFraction.of(point.exactIntValue(variable.id.index), BIG_ONE)
        is QuadraticVariable.Real -> requireNotNull(BigFraction.ofDouble(point.approximateRealValue(variable.id.index)))
    }
    return terms.map { term ->
        val product = term.second?.let { value(term.first) * value(it) } ?: value(term.first)
        BigFraction.of(term.coefficient.numerator, term.coefficient.denominator) * product
    }
}

private fun QuadraticObjectiveSpec.publishedValue(point: Sample): BigFraction =
    publishedTerms(point).fold(BigFraction.of(constant.numerator, constant.denominator)) { sum, term -> sum + term }

// Whether the floats of a widened optimum, [point], report it: their objective, an evaluation of the optimal point
// apart from rounding, agrees with the optimum [bound] across its isolating interval within the source's optimality
// gap, or within its feasibility slack when it declares no gap.
private fun PolynomialTheoryRequest.publishesOptimum(
    objective: QuadraticObjectiveSpec,
    point: QuadraticIncumbent,
    bound: QuadraticObjectiveBound,
): Boolean {
    val value = point.value.rational ?: return false
    val semantics = resolvedModel().source.semantics.effective
    val policy = semantics.optimalityTolerance ?: semantics.feasibilityTolerance?.let {
        OptimalityTolerance(it.absolute, it.relative)
    }
    val scale = objective.publishedTerms(point.sample).fold(BigFraction.ZERO) { sum, term ->
        if (term.signum() < 0) sum - term else sum + term
    }
    return withinOptimalityGap(policy, value, bound.value.lowerBound, scale) &&
        withinOptimalityGap(policy, value, bound.value.upperBound, scale)
}

private fun rationalScalar(value: BigFraction): AlgebraicReal =
    AlgebraicReal(RationalPolynomial(listOf(value.negated(), BigFraction.ONE)), 1, RationalInterval(value, value))

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
