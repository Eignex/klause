package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
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
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.QuadraticObjectiveBound
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
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
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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

// How the polynomial route reads a source's feasibility tolerance.
internal enum class PolynomialToleranceReading {
    // Acceptance reads every relation exactly, so the exact formula is the tolerance model.
    EXACT,

    // A [PolynomialTolerance] states the tolerance model, which is decided in place of the exact formula.
    WIDENED,

    // Acceptance reads a slack only relative to nonzero magnitudes, which no uniform widening states. The exact
    // formula lies inside the tolerance model: its witnesses and rays stand, and its refutations and optima do not.
    INSIDE,
    ;

    companion object {
        fun of(source: Problem): PolynomialToleranceReading {
            val policy = source.semantics.effective.feasibilityTolerance
            if (policy == null || policy.isExact || source.numRealVars == 0) return EXACT
            if (!RealToleranceScope.of(source).readsSlack) return EXACT
            return if (capturedSourceSlack(policy, BigFraction.ZERO).isZero) INSIDE else WIDENED
        }
    }
}

// This exact decision read under a tolerance [PolynomialToleranceReading.INSIDE] it.
internal fun PolynomialTheoryExecution.insideTolerance(): PolynomialTheoryExecution {
    val refuted = result as? OpenTheoryResult.Unsat ?: return this
    return copy(result = OpenTheoryResult.Unknown(TerminationReason.RepresentationDeclined, refuted.stats,
        refuted.semantics))
}

internal fun QuadraticOptimizationExecution.insideTolerance(): QuadraticOptimizationExecution {
    val reason = TerminationReason.RepresentationDeclined
    val read = when (val exact = result) {
        is QuadraticOptimizationResult.Optimal ->
            QuadraticOptimizationResult.BestFound(exact.incumbent, null, reason, exact.sense, exact.stats)
        is QuadraticOptimizationResult.UnattainedLimit ->
            QuadraticOptimizationResult.BestFound(exact.incumbent, null, reason, exact.sense, exact.stats)
        is QuadraticOptimizationResult.Infeasible ->
            QuadraticOptimizationResult.Unknown(null, reason, exact.sense, exact.stats)
        is QuadraticOptimizationResult.BestFound -> exact.copy(bound = null)
        is QuadraticOptimizationResult.Unknown -> exact.copy(bound = null)
        is QuadraticOptimizationResult.Unbounded -> return this
    }
    return copy(result = read).also { it.retainPublication(acceptedIncumbent) }
}

// The widenings a tolerant source is decided on: the declared slack decides, and a band shrunk by 2^-8 of it supplies a
// point whose floats acceptance admits when the full band holds points but every one found rounds outside it.
private val wideningScales: List<BigFraction> =
    listOf(BigFraction.ONE, BigFraction.ONE - BigFraction.of(BIG_ONE, bigIntOf(256L)))

// This request's source lowered with its slack scaled by [scale], or null when its widened encoding declines. A widened
// source enters the theory without presolve, whose reductions are exact.
private fun PolynomialTheoryRequest.widened(
    scale: BigFraction,
    cancellation: ExecutionControl,
): PolynomialTheoryRequest? {
    val source = resolvedModel().source
    val tolerance = PolynomialTolerance.of(source, scale) ?: return null
    val projections = preparedPlan.factorProjections ?: return null
    val plan = source.preparePolynomialExecutionPlan(original = preparedPlan.originalSource,
        projections = projections, cancellation = cancellation, widening = tolerance)
    return if (plan.execution is PreparedExecution.Polynomial) plan.polynomialRequest() else null
}

private fun PolynomialTheoryParams.remaining(spent: Long): PolynomialTheoryParams =
    copy(maximumWork = maximumWork?.let { (it - spent).coerceAtLeast(0) })

private fun PolynomialTheoryParams.declineReason(): TerminationReason = when {
    timeout() -> TerminationReason.Timeout
    cancellation() -> TerminationReason.Cancelled
    else -> TerminationReason.RepresentationDeclined
}

// Whether an undecided widened run found a witness whose floats acceptance refuses, rather than reaching a limit.
private fun PolynomialTheoryExecution.refusedPublication(): Boolean =
    (result as? OpenTheoryResult.Unknown)?.reason == TerminationReason.Unsupported && resource == null

private fun QuadraticOptimizationExecution.refusedPublication(): Boolean =
    (result as? QuadraticOptimizationResult.Unknown)?.reason == TerminationReason.Unsupported && resource == null

// The share of [this] an exact witness search spends before the widened decision: a quarter of what remains of the
// deadline and of the work allowance, or [EXACT_PROBE_WORK] when neither bounds the invocation. An exact point holds
// every relation, so it is a point of the tolerance model; an exact refutation or optimum decides nothing there.
internal fun PolynomialTheoryParams.exactProbe(): PolynomialTheoryParams {
    val end = timeout.deadline() ?: cancellation.deadline()
    val now = TimeSource.Monotonic.markNow()
    return copy(
        timeout = if (end == null || end <= now) timeout else {
            timeout or ExecutionControl.until(now + (end - now) / EXACT_PROBE_SHARE)
        },
        maximumWork = maximumWork?.let { it / EXACT_PROBE_SHARE } ?: EXACT_PROBE_WORK.takeIf { end == null },
    )
}

private const val EXACT_PROBE_SHARE = 4

// About two seconds of polynomial work, several times what the exact witnesses of small float systems take.
private const val EXACT_PROBE_WORK = 1L shl 22

// The decision of this request's tolerant source on its widened formula, after an exact witness search [probe] in its
// own share of [params], whose witness decides and whose other results are discarded. A widened refutation refutes the
// tolerance model and a limit leaves it undecided. When the full band holds points but none found prints floats
// acceptance admits, the shrunken band supplies one; if it holds none, the source is satisfiable within the tolerance
// but no publishable point is known, which is undecided and never a refutation.
internal fun PolynomialTheoryRequest.executeWidened(
    params: PolynomialTheoryParams,
    engine: FiniteEngine,
    cores: Int,
    probe: PolynomialTheoryExecution?,
): PolynomialTheoryExecution {
    if (probe?.result is OpenTheoryResult.Sat) return probe
    var stats = probe?.stats ?: PolynomialTheoryStats()
    var last: PolynomialTheoryExecution? = null
    fun undecided(reason: TerminationReason) = PolynomialTheoryExecution(
        OpenTheoryResult.Unknown(reason, last?.result?.stats ?: SolveStats.EMPTY), stats,
    ).withSourceSemantics(preparedPlan.sourceSemantics)
    for ((attempt, scale) in wideningScales.withIndex()) {
        val stop = params.timeout.includingStop(params.cancellation)
        val widened = (if (stop()) null else widened(scale, stop)) ?: return undecided(params.declineReason())
        val execution = OpenTheoryPipeline.execute(widened, params.remaining(stats.workSpent), engine, cores)
        stats = stats.plus(execution.stats)
        last = execution
        when (execution.result) {
            is OpenTheoryResult.Sat -> return execution.copy(stats = stats)
            is OpenTheoryResult.Unsat -> return if (attempt == 0) {
                execution.copy(stats = stats)
            } else {
                undecided(TerminationReason.RepresentationDeclined)
            }
            is OpenTheoryResult.Unknown -> if (!execution.refusedPublication()) return execution.copy(stats = stats)
        }
    }
    return undecided(TerminationReason.RepresentationDeclined)
}

// The optimization counterpart of [executeWidened] for an affine objective, after an exact witness search [probe]
// that publishes through the observer it is given. The full band's optimum is the optimum within the tolerance. Every
// other point is at best the best point found: an exact one, the shrunken band's optimum when the full band's floats
// fall outside acceptance, and the best published point of a run that stops without a verdict. Publications are
// renumbered across the runs and forwarded only when they improve, so callers see one improving sequence.
internal fun PolynomialTheoryRequest.optimizeWidened(
    objective: LinearObjective,
    params: PolynomialTheoryParams,
    engine: FiniteEngine,
    sense: ObjectiveSense,
    cores: Int,
    onAccepted: ((QuadraticIncumbentPublication) -> Unit)?,
    probe: ((QuadraticIncumbentPublication) -> Unit) -> QuadraticOptimizationExecution?,
): QuadraticOptimizationExecution {
    var work = 0L
    var version = 0L
    var published: QuadraticIncumbentPublication? = null
    var bound: QuadraticObjectiveBound? = null
    var last: QuadraticOptimizationExecution? = null
    val order = AlgebraicArithmetic()
    fun forward(accepted: QuadraticIncumbentPublication) {
        val renumbered = accepted.copy(version = ++version)
        published = renumbered
        onAccepted?.invoke(renumbered)
    }
    val observe: (QuadraticIncumbentPublication) -> Unit = { accepted ->
        val current = published?.incumbent?.value
        val step = current?.let { order.compare(accepted.incumbent.value, it) }
        if (step == null || (if (sense == ObjectiveSense.MINIMIZE) step < 0 else step > 0)) forward(accepted)
    }
    fun concluded(reason: TerminationReason): QuadraticOptimizationExecution {
        val stats = last?.result?.stats ?: SolveStats.EMPTY
        val point = published
        val result = if (point == null) {
            QuadraticOptimizationResult.Unknown(bound, reason, sense, stats)
        } else {
            QuadraticOptimizationResult.BestFound(point.incumbent, bound, reason, sense, stats)
        }
        val base = last ?: QuadraticOptimizationExecution(result, PolynomialTheoryStats(), 0L, work)
        return base.copy(result = result, nativeWork = work).also { it.retainPublication(point) }
            .withSourceSemantics(preparedPlan.sourceSemantics)
    }
    fun settled(execution: QuadraticOptimizationExecution): QuadraticOptimizationExecution {
        execution.acceptedIncumbent?.takeIf { it.incumbent !== published?.incumbent }?.let(::forward)
        return execution.copy(nativeWork = work).also { it.retainPublication(published) }
    }
    probe(observe)?.let { work += it.nativeWork }
    for ((attempt, scale) in wideningScales.withIndex()) {
        val stop = params.timeout.includingStop(params.cancellation)
        val widened = (if (stop()) null else widened(scale, stop)) ?: return concluded(params.declineReason())
        val execution = OpenTheoryPipeline.optimize(widened, objective, params.remaining(work), engine, sense, cores,
            observe)
        work += execution.nativeWork
        last = execution
        val result = execution.result
        if (attempt == 0) bound = (result as? QuadraticOptimizationResult.Unknown)?.bound
            ?: (result as? QuadraticOptimizationResult.BestFound)?.bound
        when (result) {
            is QuadraticOptimizationResult.Unknown -> if (!execution.refusedPublication()) {
                return concluded(result.reason)
            }
            // A ray of the shrunken band is one of the full band.
            is QuadraticOptimizationResult.Unbounded -> return settled(execution)
            is QuadraticOptimizationResult.BestFound -> return concluded(result.reason)
            // An exact point refutes the refutation, which then names no verdict.
            is QuadraticOptimizationResult.Infeasible -> return if (attempt == 0 && published == null) {
                settled(execution)
            } else concluded(TerminationReason.RepresentationDeclined)
            else -> return if (attempt == 0) settled(execution) else concluded(TerminationReason.RepresentationDeclined)
        }
    }
    return concluded(TerminationReason.RepresentationDeclined)
}

private fun SolveStats.since(started: TimeMark): SolveStats =
    copy(run = run.copy(wallMs = started.elapsedNow().inWholeMilliseconds))

// This decision with its wall time spanning everything since [started]: presolve, the exact witness search and every
// band run, of which the result's own statistics cover only the last.
internal fun PolynomialTheoryExecution.since(started: TimeMark): PolynomialTheoryExecution = copy(
    result = when (val decided = result) {
        is OpenTheoryResult.Sat -> decided.copy(stats = decided.stats.since(started))
        is OpenTheoryResult.Unsat -> decided.copy(stats = decided.stats.since(started))
        is OpenTheoryResult.Unknown -> decided.copy(stats = decided.stats.since(started))
    },
)

internal fun QuadraticOptimizationExecution.since(started: TimeMark): QuadraticOptimizationExecution =
    copy(result = result.withPortfolioStats(result.stats.since(started))).also {
        it.retainPublication(acceptedIncumbent)
    }

private fun PolynomialTheoryStats.plus(other: PolynomialTheoryStats): PolynomialTheoryStats = copy(
    projectedPolynomials = projectedPolynomials + other.projectedPolynomials,
    liftedCells = liftedCells + other.liftedCells,
    workSpent = workSpent + other.workSpent,
)
