package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.lowering.polynomialRow
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.RealToleranceScope
import com.eignex.klause.solver.validation.ToleranceReading
import com.eignex.klause.solver.validation.capturedSourceSlack
import com.eignex.klause.solver.validation.readsSlack
import com.eignex.klause.solver.validation.slackCoordinates
import com.eignex.klause.solver.validation.toleranceReading
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.includingStop
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf

// The tolerance a widened polynomial encoding of [source] states. Each declaration and relation that
// [RealToleranceScope] reads within the slack is widened by [slack], at most the slack the source declares for a zero
// bound: an active non-strict row by that slack around its right-hand side, `r = f(x)` by stating `r' = f(x)` over a
// private `r'` with `abs(r - r') <= slack`, and a finite membership by moving its coordinate the same way. Every other
// relation and declaration stays exact, including a relation whose read coordinate also states an operand, which
// acceptance reads within the slack. The scope's slack is never below this one, so the widened region lies inside
// the source acceptance relation by relation, and a refutation of the widened formula refutes every point that holds
// each relation within [slack].
internal class PolynomialTolerance private constructor(
    private val source: Problem,
    private val policy: SourceTolerance,
    private val scope: RealToleranceScope,
    private val slack: BigFraction,
) {
    // Whether this encoding widens [factor] rather than stating it exactly.
    fun widens(factor: Factor): Boolean {
        val reals = factor.variables.reals
        return reals.isNotEmpty() && factor !is Distinct && !scope.isExact(reals[0])
    }

    // The slack of real declaration [id], or null when it holds exactly.
    fun declaration(id: Int): BigFraction? = if (scope.isExact(id)) null else slack

    // The coordinates of widened [factor] that its exact encoding states over private reals, or null when its rows
    // are widened in place or it holds exactly.
    fun substitutions(factor: Factor): IntArray? = factor.slackCoordinates()

    // Whether widened [factor] is read as rows, so that [rows] states it.
    fun readsRows(factor: Factor): Boolean = factor.toleranceReading() == ToleranceReading.ROWS

    // [factor]'s rows with every active non-strict row widened by the slack.
    fun rows(factor: Factor, encoding: PolynomialEncodingContext): PolynomialRealFormula {
        fun row(row: LinearRow) = polynomialRow(row, { encoding.integer(SourceIntId(it)) },
            { encoding.real(SourceRealId(it)) }, encoding::conditional, slack.takeIf { row.readsSlack })
        if (factor is LinearIndicator) {
            val inactive = PolynomialRealFormula.Atom(encoding.integer(SourceIntId(factor.indicator)),
                PolynomialRelation.NE, PolynomialRealLowering.constant(
                    BigFraction.ofLong(if (factor.whenOne) 1 else 0)))
            return PolynomialRealFormula.Or(listOf(inactive, row(factor.row)))
        }
        val form = checkNotNull(factor.linearForm)
        val rows = form.rows.map(::row)
        return if (form is LinearForm.Disjunction) PolynomialRealFormula.Or(rows) else PolynomialRealFormula.And(rows)
    }

    // `abs(value - exact) <= slack`.
    fun band(value: PolynomialExpression, exact: PolynomialExpression): PolynomialRealFormula {
        val residual = PolynomialRealLowering.sum(listOf(value, PolynomialRealLowering.negate(exact)))
        return PolynomialRealFormula.And(listOf(
            PolynomialRealFormula.Atom(residual, PolynomialRelation.LE, PolynomialRealLowering.constant(slack)),
            PolynomialRealFormula.Atom(residual, PolynomialRelation.GE,
                PolynomialRealLowering.constant(slack.negated())),
        ))
    }

    // The source acceptance of [sample], the one the finite lane applies.
    fun accepts(sample: Sample, cancellation: ExecutionControl): Verification<Sample, Unit> =
        scope.verify(source, sample, policy, cancellation, integerRows = false)

    companion object {
        // The widening [source] declares, scaled by [scale], or null when it declares no slack for a zero bound or
        // no relation reads the slack.
        fun of(source: Problem, scale: BigFraction = BigFraction.ONE): PolynomialTolerance? {
            val policy = source.semantics.effective.feasibilityTolerance
            if (policy == null || policy.isExact || source.numRealVars == 0) return null
            val slack = capturedSourceSlack(policy, BigFraction.ZERO) * scale
            if (slack.isZero) return null
            val scope = RealToleranceScope.of(source)
            return if (scope.readsSlack) PolynomialTolerance(source, policy, scope, slack) else null
        }
    }
}

// The widenings tried after an exact refutation: the declared slack decides, and a band shrunk by 2^-8 of it supplies a
// point whose floats acceptance admits when every point of the full band rounds outside it.
private val wideningScales: List<BigFraction> =
    listOf(BigFraction.ONE, BigFraction.ONE - BigFraction.of(BIG_ONE, bigIntOf(256L)))

// This request's source lowered with its slack scaled by [scale], or null when the source declares no widening or
// its widened encoding declines. A widened source enters the theory without presolve, whose reductions are exact.
private fun PolynomialTheoryRequest.widened(scale: BigFraction, cancellation: ExecutionControl): PolynomialTheoryRequest? {
    val source = resolvedModel().source
    val tolerance = PolynomialTolerance.of(source, scale) ?: return null
    val projections = preparedPlan.factorProjections ?: return null
    val plan = source.preparePolynomialExecutionPlan(original = preparedPlan.originalSource,
        projections = projections, cancellation = cancellation, widening = tolerance)
    return if (plan.execution is PreparedExecution.Polynomial) plan.polynomialRequest() else null
}

private fun PolynomialTheoryParams.remaining(spent: Long): PolynomialTheoryParams =
    copy(maximumWork = maximumWork?.let { (it - spent).coerceAtLeast(0) })

// Whether an undecided widened run found a witness whose floats acceptance refuses, rather than reaching a limit.
private fun PolynomialTheoryExecution.refusedPublication(): Boolean =
    (result as? OpenTheoryResult.Unknown)?.reason == TerminationReason.Unsupported && resource == null

private fun QuadraticOptimizationExecution.refusedPublication(): Boolean =
    (result as? QuadraticOptimizationResult.Unknown)?.reason == TerminationReason.Unsupported && resource == null

// After the exact formula of this request's source is refuted, the decision over the widened formula in what remains
// of [params], counting [spent] in its statistics and allowance. Null keeps the exact refutation: when the source
// declares no widening, when the widened decision reaches a limit, and when it finds points but none whose floats
// acceptance admits.
internal fun PolynomialTheoryRequest.executeWidened(
    params: PolynomialTheoryParams,
    engine: FiniteEngine,
    cores: Int,
    spent: PolynomialTheoryStats,
): PolynomialTheoryExecution? {
    var stats = spent
    for ((attempt, scale) in wideningScales.withIndex()) {
        val stop = params.timeout.includingStop(params.cancellation)
        val widened = (if (stop()) null else widened(scale, stop)) ?: return null
        val execution = OpenTheoryPipeline.execute(widened, params.remaining(stats.workSpent), engine, cores)
        stats = stats.plus(execution.stats)
        when (execution.result) {
            is OpenTheoryResult.Sat -> return execution.copy(stats = stats)
            // A shrunken band refutes nothing beyond itself.
            is OpenTheoryResult.Unsat -> return if (attempt == 0) execution.copy(stats = stats) else null
            is OpenTheoryResult.Unknown -> if (!execution.refusedPublication()) return null
        }
    }
    return null
}

// The widened counterpart of [executeWidened] for an affine objective after an exact infeasibility, with [spent]
// native work already charged. An optimum over a shrunken band, and the last published point of a run that stops
// without a verdict, are the best points found: the full band may hold a better one. Null keeps the exact
// infeasibility when no widened point was published.
internal fun PolynomialTheoryRequest.optimizeWidened(
    objective: LinearObjective,
    params: PolynomialTheoryParams,
    engine: FiniteEngine,
    sense: ObjectiveSense,
    cores: Int,
    spent: Long,
    onAccepted: ((QuadraticIncumbentPublication) -> Unit)?,
): QuadraticOptimizationExecution? {
    var work = spent
    var published: QuadraticIncumbentPublication? = null
    val observe: (QuadraticIncumbentPublication) -> Unit = { accepted ->
        published = accepted
        onAccepted?.invoke(accepted)
    }
    fun bestFound(execution: QuadraticOptimizationExecution, reason: TerminationReason) = published?.let {
        execution.copy(result = QuadraticOptimizationResult.BestFound(it.incumbent, null, reason, sense,
            execution.result.stats)).also { best -> best.retainPublication(it) }
    }
    for ((attempt, scale) in wideningScales.withIndex()) {
        val stop = params.timeout.includingStop(params.cancellation)
        val widened = (if (stop()) null else widened(scale, stop)) ?: return null
        val execution = OpenTheoryPipeline.optimize(widened, objective, params.remaining(work), engine, sense, cores,
            observe)
        work += execution.nativeWork
        val result = execution.result
        when {
            result is QuadraticOptimizationResult.Infeasible -> return if (attempt == 0) execution
                else bestFound(execution, TerminationReason.RepresentationDeclined)
            result is QuadraticOptimizationResult.Unknown -> if (!execution.refusedPublication()) {
                return bestFound(execution, result.reason)
            } else if (attempt == wideningScales.lastIndex) {
                return bestFound(execution, TerminationReason.RepresentationDeclined)
            }
            attempt > 0 && result is QuadraticOptimizationResult.Optimal ->
                return bestFound(execution, TerminationReason.RepresentationDeclined)
            else -> return execution
        }
    }
    return null
}

private fun PolynomialTheoryStats.plus(other: PolynomialTheoryStats): PolynomialTheoryStats = copy(
    projectedPolynomials = projectedPolynomials + other.projectedPolynomials,
    liftedCells = liftedCells + other.liftedCells,
    workSpent = workSpent + other.workSpent,
)
