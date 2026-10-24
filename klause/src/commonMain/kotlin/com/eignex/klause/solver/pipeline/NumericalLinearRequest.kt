package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.finiteRelaxationSupported
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.hasFiniteIntegerRanges
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.uuid.Uuid

/** Numerical LP/MIP execution with source certification and no arithmetic-theory fallback. */
class NumericalLinearRequest internal constructor(
    private val model: Problem,
    /** Original-direction objective, or null for feasibility. */
    val objective: LinearObjective?,
    /** Whether the original objective is maximized. */
    val maximize: Boolean,
    private val cancellation: Cancellation,
    private val boundOrdered: Boolean,
) {
    /** Construct numerical traversal with its original source objective and cancellation scope. */
    constructor(
        model: Problem,
        objective: LinearObjective? = null,
        maximize: Boolean = false,
        cancellation: Cancellation = Cancellation.Never,
    ) : this(model, objective, maximize, cancellation, false)

    /** Whether native exact rows admit finite working coefficients without losing a nonzero source term. */
    fun numericalRowsSupported(stop: Cancellation = Cancellation.Never): Boolean {
        val token = cancellation or stop
        if (token()) return false
        for (factor in model.factors) {
            if (token()) return false
            val supported = when (factor) {
                is ExactLinear -> factor.finiteRelaxationSupported()
                is LinearIndicator -> factor.row.finiteRelaxationSupported()
                else -> true
            }
            if (!supported) return false
        }
        return true
    }

    /**
     * Finite numerical execution or a source refutation from numerical bound closure.
     * Null retains numerical traversal over the original open sides.
     */
    fun finiteRoute(onLpStats: (LpStats) -> Unit = {}): SourceProblemRoute? = finiteRoute(cancellation, onLpStats)

    internal fun finiteRoute(boundCancellation: Cancellation, onLpStats: (LpStats) -> Unit): SourceProblemRoute? {
        if (!numericalRowsSupported()) return null
        var routed = model
        if (!routed.hasFiniteIntegerRanges()) {
            val sink = LpStatsSink(LpRoute.STANDALONE)
            val closed = routed.closeOpenBounds(boundCancellation or cancellation, sink)
            onLpStats(sink.snapshot())
            if (closed == OpenPresolveResult.Refuted) return SourceProblemRoute.Refuted
            routed = (closed as OpenPresolveResult.Tightened).spec
            if (!routed.hasFiniteIntegerRanges()) return null
            routed = routed.withIntDomains(routed.finiteIntDomains())
        }
        if (!routed.finiteRealCompletionSupported()) return null
        val prepared = routed.prepareExecutionPlan(
            model,
            objective,
            maximize,
            preferFinite = true,
            cancellation = cancellation,
        )
        val finite = prepared.execution as? PreparedExecution.FiniteLinear ?: return null
        val ownership = finite.ownership
        return if (ownership.theoryPipeline == ProblemPipeline.FINITE_CP) {
            SourceProblemRoute.Finite(finite.model, ownership, prepared)
        } else {
            null
        }
    }

    /**
     * Traverse source integer splits using numerical relaxations and checked source derivations.
     * Unresolved leaves retain witnesses and root bounds without a terminal claim.
     * Passing `false` for [complete] withholds bounds and terminals for witness-only callers.
     */
    fun solve(
        params: TheoryParams = TheoryParams(),
        complete: Boolean = true,
        onIncumbent: (Sample, BigFraction) -> Unit = { _, _ -> },
    ): LinearOptimizationResult {
        val stop = cancellation or params.timeout or params.cancellation
        val context = authorityContext(objective ?: LinearObjective(), stop) ?: return unavailable(params, stop)
        return LinearAuthorityInvocation(context, params.copy(cancellation = stop),
            boundOrdered = boundOrdered).use { invocation ->
            val result = invocation.run(onIncumbent)
            LinearOptimizationResult(
                context.scope,
                result.terminal.takeIf { complete },
                result.bound.takeIf { complete },
                result.incumbent,
                result.value,
                result.reason,
                result.stats.copy(run = result.stats.run.copy(backend = "numerical-mip")),
                context.semantics,
            )
        }
    }

    internal fun solve(
        params: TheoryParams,
        engine: FiniteEngine,
        cores: Int,
        preparedPlan: PreparedSourcePlan,
        publication: SourcePublicationObserver,
    ): LinearOptimizationResult {
        require(cores > 0)
        if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            return repair(params, engine == FiniteEngine.ALNS, publication)
        }
        if (engine == FiniteEngine.FIXED) {
            return solve(params, onIncumbent = { sample, value ->
                publication.accepted(sample, SourceValue.Rational(value))
            })
        }
        if (engine != FiniteEngine.BACKTRACK && engine != FiniteEngine.MIXED) {
            return unavailable(params, cancellation or params.timeout or params.cancellation)
        }
        val originalObjective = objective ?: LinearObjective()
        val workingPlan = preparedPlan.numericalWorkingPlan(model, originalObjective, maximize)
        val stop = cancellation or params.timeout or params.cancellation
        val context = authorityContext(originalObjective, stop) ?: return unavailable(params, stop)
        val executionObjective = if (maximize) originalObjective.negated() else originalObjective
        val portfolio = OpenPortfolio(
            model, null, params.copy(cancellation = stop), seed = params.randomSeed, lanes = cores,
            sourceObjective = OpenSourceObjective(executionObjective, originalObjective, maximize, context, stop),
            publication = publication, preparedSource = workingPlan, completeOnly = true, numericalOnly = true,
            boundOrdered = boundOrdered,
        )
        val result = portfolio.minimize(executionObjective, null, stop).linearResult ?: return unavailable(params, stop)
        return LinearOptimizationResult(
            result.scope,
            result.terminal,
            result.bound,
            result.incumbent,
            result.value,
            result.reason,
            result.stats.copy(run = result.stats.run.copy(backend = "numerical-mip")),
            result.semantics,
        )
    }

    private fun repair(
        params: TheoryParams,
        adaptive: Boolean,
        publication: SourcePublicationObserver,
    ): LinearOptimizationResult {
        val stop = cancellation or params.timeout or params.cancellation
        val context = authorityContext(objective ?: LinearObjective(), stop) ?: return unavailable(params, stop)
        if (model.numBoolVars != 0) return unavailable(params, stop)
        val executionObjective = if (maximize) objective?.negated() else objective
        return JointLinearRepair(model, context, executionObjective, params.copy(cancellation = stop),
            boundOrdered = boundOrdered).use { repair ->
            repair.search(adaptive).use { search ->
                var incumbent: Sample? = null
                var value: BigFraction? = null
                var done = false
                while (!done && !stop() && !repair.exhausted) {
                    done = search.advance(stop, Long.MAX_VALUE) { sample, _ ->
                        val original = context.linearValue(sample, stop) ?: return@advance
                        incumbent = sample
                        value = original
                        publication.accepted(sample, SourceValue.Rational(original))
                    }
                }
                val reason = when {
                    params.timeout() -> TerminationReason.Timeout
                    stop() -> TerminationReason.Cancelled
                    else -> TerminationReason.BudgetExhausted
                }
                search.close()
                val stats = search.stats
                LinearOptimizationResult(context.scope, null, null, incumbent, value, reason,
                    stats.copy(run = stats.run.copy(backend = "numerical-joint-repair")),
                    context.semantics)
            }
        }
    }

    private fun authorityContext(objective: LinearObjective, stop: Cancellation) =
        if (numericalRowsSupported(stop)) {
            model.linearAuthorityContext(
            objective,
            if (maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE,
            stop,
            numericalIndicators = true,
        )
        } else {
            null
        }

    private fun unavailable(params: TheoryParams, stop: Cancellation): LinearOptimizationResult {
        val identity = Uuid.random().toString()
        val scope = SourceScope(
            SourceRevision(identity, "klause-ir-exact-v1"),
            objective = SourceObjective(
                "$identity/objective",
                if (maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE,
            ),
        )
        val reason = when {
            params.timeout() -> TerminationReason.Timeout
            stop() -> TerminationReason.Cancelled
            else -> TerminationReason.Unsupported
        }
        return LinearOptimizationResult(scope, null, null, null, null, reason, SolveStats.EMPTY, model.semantics)
    }
}
