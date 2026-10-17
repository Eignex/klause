package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.hasFiniteIntegerRanges
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.util.Cancellation

/** The solver pipeline selected once from a source [Problem]. */
enum class ProblemPipeline {
    /**
     * Finite search and optimization route.
     *
     * Source setup selects this execution family independently of variable ownership.
     * [Problem.componentPlan] can describe complete arithmetic mechanisms for the same finite source.
     */
    FINITE_CP,

    /** Open integer sides are covered entirely by difference logic. */
    DIFFERENCE_THEORY,

    /** Open pure-real linear arithmetic, decided by the exact rational simplex under Boolean search. */
    EXACT_LRA,

    /** Open integer or mixed integer/real linear arithmetic, decided by exact LP and integer branching. */
    EXACT_LIRA,

    /** An open integer side reaches a factor no available theory decides. */
    UNSUPPORTED_OPEN,
}

/** A source model prepared for the finite or complete open-theory pipeline. */
sealed interface SourceProblemRoute {
    /** Captured exact source formula, with no invented factor model or finite declarations. */
    class NativeFormula internal constructor(
        internal val execution: NativeSourceExecution?,
        internal val preparationReason: com.eignex.klause.solver.result.TerminationReason? =
            if (execution == null) com.eignex.klause.solver.result.TerminationReason.Cancelled else null,
    ) : SourceProblemRoute {
        /** Completed native ownership, absent when source preparation was interrupted. */
        val preparedPlan: PreparedSourcePlan? get() = execution?.preparedPlan
    }

    /** Exact polynomial completion beside a finite projection of the shared source model. */
    data class PolynomialTheory(
        /** Source decomposition and exact continuous constraints. */
        val request: PolynomialTheoryRequest,
        /** Original affine source objective, or null for satisfaction. */
        val objective: LinearObjective? = null,
        /** Whether [objective] is maximized. */
        val maximize: Boolean = false,
    ) : SourceProblemRoute {
        /** Shared source preparation used by every polynomial strategy. */
        val preparedPlan: PreparedSourcePlan get() = request.preparedPlan
    }

    /** A fully bounded source model selected for deferred finite-domain preparation. */
    data class Finite(
        /** Canonical logical model retained until deferred finite preparation. */
        val problem: Problem,
        /** Component ownership selected from the same canonical source model. */
        val componentPlan: ComponentPlan,
        /** Shared source preparation retaining the original source identity. */
        val preparedPlan: PreparedSourcePlan = problem.prepareExecutionPlan(preferFinite = true),
    ) : SourceProblemRoute

    /** A supported open source model with its selected theory request. */
    data class OpenTheory(
        /** The complete open-theory request. */
        val request: OpenTheoryRequest,
    ) : SourceProblemRoute {
        /** Shared source preparation used by every open strategy. */
        val preparedPlan: PreparedSourcePlan get() = request.preparedPlan
    }

    /** An open source model for which no complete route exists. */
    data class UnsupportedOpen(
        /** The column and factor that prevented routing, when one specific column caused the refusal. */
        val unplaceable: UnplaceableColumn?,
        /** The model as routing proved it, which local search can still search for a witness. */
        val problem: Problem,
        /** Prepared ownership and explicit completion decline. */
        val preparedPlan: PreparedSourcePlan = problem.prepareExecutionPlan(),
    ) : SourceProblemRoute

    /**
     * Bounding the model's open sides refuted it, so no lane needs to run.
     *
     * Derived over the genuinely open ranges rather than inside an invented box, which is what makes it
     * the unbounded model's own verdict and reportable as `unsat`. A caller that dropped this would ask a
     * lane to re-derive the same proof — or, where the model is one no lane accepts, would report it
     * unsupported having already decided it.
     */
    data object Refuted : SourceProblemRoute
}

/**
 * Select the same source route as [SourceSetup.prepare].
 *
 * [routePureRealToTheory] and [routeLinearToTheory] are compatibility arguments; source mathematics and
 * objective precision determine ownership. [boundCancellation] bounds optional deduction and required admission.
 */
@Suppress("UNUSED_PARAMETER")
fun Problem.pipelineRoute(
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    routePureRealToTheory: Boolean = false,
    boundCancellation: Cancellation = Cancellation.Never,
    routeLinearToTheory: Boolean = false,
): SourceProblemRoute = sharedPipelineRoute(objective, maximize, boundCancellation, boundCancellation)

/**
 * Select the shared source route and observe bound-probing LP work.
 * [routePureRealToTheory] and [routeLinearToTheory] retain compatibility without overriding source ownership.
 */
@Suppress("UNUSED_PARAMETER")
fun Problem.pipelineRoute(
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    routePureRealToTheory: Boolean = false,
    boundCancellation: Cancellation = Cancellation.Never,
    routeLinearToTheory: Boolean = false,
    onLpStats: (LpStats) -> Unit,
): SourceProblemRoute = sharedPipelineRoute(
    objective, maximize, boundCancellation, boundCancellation, onLpStats = onLpStats,
)

/**
 * Select the shared source route with per-invocation factor projection providers.
 * [routePureRealToTheory] and [routeLinearToTheory] retain compatibility without overriding source ownership.
 */
@Suppress("UNUSED_PARAMETER")
fun Problem.pipelineRoute(
    providers: FactorProjectionProviders,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    routePureRealToTheory: Boolean = false,
    boundCancellation: Cancellation = Cancellation.Never,
    routeLinearToTheory: Boolean = false,
    onLpStats: ((LpStats) -> Unit)? = null,
): SourceProblemRoute = sharedPipelineRoute(
    objective, maximize, boundCancellation, boundCancellation, providers, onLpStats,
)

/**
 * Select the shared source route with separate optional deduction and required admission allowances.
 *
 * [boundCancellation] stops optional deductions. [preparationCancellation] bounds complete source
 * installation and retains the containing invocation's deadline and work allowance. Completed bounds
 * survive either stop. [routePureRealToTheory] and [routeLinearToTheory] retain compatibility without
 * overriding source ownership.
 */
@Suppress("UNUSED_PARAMETER")
fun Problem.pipelineRoute(
    boundCancellation: Cancellation,
    preparationCancellation: Cancellation,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    routePureRealToTheory: Boolean = false,
    routeLinearToTheory: Boolean = false,
    providers: FactorProjectionProviders = (this as? BakedProblem)?.projectionProviders
        ?: FactorProjectionProviders.Default,
    onLpStats: ((LpStats) -> Unit)? = null,
): SourceProblemRoute = sharedPipelineRoute(
    objective, maximize, boundCancellation, preparationCancellation, providers, onLpStats,
)

private fun Problem.sharedPipelineRoute(
    objective: LinearObjective?,
    maximize: Boolean,
    boundCancellation: Cancellation,
    preparationCancellation: Cancellation,
    providers: FactorProjectionProviders? = null,
    onLpStats: ((LpStats) -> Unit)? = null,
): SourceProblemRoute {
    val source = SourceAttachment(this)
    val request = SourceSetupRequest(
        source,
        if (objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE,
        FiniteEngine.MIXED,
        objective,
        maximize,
        providers ?: source.providers,
        preparationCancellation,
        boundCancellation,
    )
    return if (onLpStats == null) SourceSetup.prepare(request).route
    else SourceSetup.prepare(request, onLpStats).route
}

internal fun Problem.pipelineRoute(
    projections: PreparedFactorProjections,
    objective: LinearObjective?,
    maximize: Boolean,
    boundCancellation: Cancellation,
    preparationCancellation: Cancellation,
    polynomialCancellation: Cancellation = preparationCancellation,
    onLpStats: ((LpStats) -> Unit)? = null,
): SourceProblemRoute = pipelineRouteObserved(
    objective, maximize, true, boundCancellation, onLpStats,
    requiresExactObjectiveRoute(objective, preparationCancellation),
    projections.providers, projections, polynomialCancellation = polynomialCancellation,
    preparationCancellation = preparationCancellation,
)

internal fun Problem.pipelineRoute(
    projections: PreparedFactorProjections,
    routePureRealToTheory: Boolean,
    boundCancellation: Cancellation,
    polynomialCancellation: Cancellation = boundCancellation,
): SourceProblemRoute = pipelineRouteObserved(
    null, false, routePureRealToTheory, boundCancellation, null, false,
    projections.providers, projections, polynomialCancellation,
)

private fun Problem.pipelineRouteObserved(
    objective: LinearObjective?,
    maximize: Boolean,
    routePureRealToTheory: Boolean,
    boundCancellation: Cancellation,
    onLpStats: ((LpStats) -> Unit)?,
    routeLinearToTheory: Boolean,
    providers: FactorProjectionProviders = FactorProjectionProviders.Default,
    projectionCapture: PreparedFactorProjections? = null,
    polynomialCancellation: Cancellation = boundCancellation,
    preparationCancellation: Cancellation = boundCancellation,
): SourceProblemRoute {
    val selection = projectionCapture ?: providers.prepare(this)
    val source = if (requiresPolynomialCompletion(selection)) {
        null
    } else {
        prepareExecutionPlan(this, null, false, false, providers, selection, preparationCancellation)
    }
    val initial = source?.let { objective?.let { objective -> it.withObjective(objective, maximize) } ?: it }
    if (initial?.decline == PreparedPlanDecline.RESOURCE) {
        return SourceProblemRoute.UnsupportedOpen(null, this, initial)
    }
    val lpStats = onLpStats?.let { LpStatsSink(LpRoute.STANDALONE) }
    val boundsStop = if (boundCancellation === preparationCancellation) {
        boundCancellation
    } else {
        boundCancellation or preparationCancellation
    }
    val bounded = proveBounded(boundsStop, lpStats)
    if (lpStats != null) onLpStats(lpStats.snapshot())
    val routed = when (bounded) {
        BoundedRouting.Refuted -> return SourceProblemRoute.Refuted
        is BoundedRouting.Proved -> bounded.problem
    }
    val finiteIntegerRanges = routed.hasFiniteIntegerRanges()
    val projections = selection.forEngineView(routed)
    val reuseInitial = initial != null && routed === this && initial.factorProjections === projections &&
        initial.objective === objective && initial.maximize == maximize
    val stopped = reuseInitial && preparationCancellation()
    val preparationStop = if (stopped) Cancellation { true } or preparationCancellation else preparationCancellation
    val finiteCompletion = finiteIntegerRanges &&
        objective?.realCoefficientValues?.any { it != 0.0 } != true &&
        routed.requiresPolynomialCompletion(projections) && routed.finiteRealCompletionSupported(projections)
    val arithmeticStop = if (routed.requiresPolynomialCompletion(projections, preferFinite = finiteCompletion)) {
        polynomialCancellation
    } else {
        preparationStop
    }
    val arithmetic = if (reuseInitial && !stopped) {
        requireNotNull(initial)
    } else {
        routed.prepareExecutionPlan(
            this,
            objective,
            maximize,
            preferFinite = finiteCompletion,
            providers,
            projections,
            arithmeticStop,
        )
    }
    val arithmeticExecution = when (val execution = arithmetic.execution) {
        is PreparedExecution.Declined -> return SourceProblemRoute.UnsupportedOpen(null, routed, arithmetic)

        is PreparedExecution.Polynomial -> return SourceProblemRoute.PolynomialTheory(
            arithmetic.polynomialRequest(),
            objective,
            maximize,
        )

        is PreparedExecution.FiniteLinear -> execution

        is PreparedExecution.NonlinearInteger, is PreparedExecution.Transcendental,
        is PreparedExecution.Numerical,
        ->
            error("factor source requires factor execution")
    }
    val arithmeticRoute = arithmeticExecution.ownership.theoryPipeline
    val preferExact = routeLinearToTheory &&
        (arithmeticRoute == ProblemPipeline.EXACT_LRA || arithmeticRoute == ProblemPipeline.EXACT_LIRA)
    val preferFinite = finiteIntegerRanges && !preferExact &&
        (!routePureRealToTheory || arithmeticRoute != ProblemPipeline.EXACT_LRA) &&
        routed.finiteRealCompletionSupported(projections)
    val prepared = if (preferFinite) {
        routed.prepareExecutionPlan(
            this,
            objective,
            maximize,
            preferFinite = true,
            providers,
            projections,
            preparationStop,
        )
    } else {
        arithmetic
    }
    val selected = when (val execution = prepared.execution) {
        is PreparedExecution.Declined -> return SourceProblemRoute.UnsupportedOpen(null, routed, prepared)
        is PreparedExecution.FiniteLinear -> execution
        else -> error("finite preference requires finite/linear execution")
    }
    val plan = selected.ownership
    if (plan.theoryPipeline == ProblemPipeline.FINITE_CP) {
        return SourceProblemRoute.Finite(routed, plan, prepared)
    }
    val request = OpenTheoryRequest(routed, objective, maximize, plan, preparedPlan = prepared)
    return if (request.route == ProblemPipeline.UNSUPPORTED_OPEN || request.route == ProblemPipeline.FINITE_CP) {
        SourceProblemRoute.UnsupportedOpen(request.componentPlan.unplaceable, routed, prepared)
    } else {
        SourceProblemRoute.OpenTheory(request)
    }
}

/**
 * This model with every open integer side replaced by a bound optimization-based tightening proves, or
 * this model unchanged when any side survives open.
 *
 * A source that states no upper bound is not thereby a model for a complete theory: the relaxation
 * routinely proves one, and a model every side of which is proved finite is a finite model that the
 * finite lane owns. Deciding that here rather than after the lane is chosen is what stops a proved-finite
 * model being handed to the exact theory, which cannot use the proof and can spend the whole budget inside
 * one exact reduction.
 *
 * A closure that reaches only some of the open sides is kept too. Which lane runs is not all that reads
 * these bounds: [componentPlan] owns a column CP or THEORY by whether its sides are stated, and an open
 * column some CP-only factor reads is what refuses a model outright. So dropping a partial closure can
 * decline a model on account of the very column the proof had just bounded. Tightening only ever closes
 * sides, so keeping it can move a column to CP or admit a complete fragment, never the reverse.
 *
 * A refutation the bounding derives is the model's verdict and travels as one; see [BoundedRouting].
 */
private fun Problem.proveBounded(cancellation: Cancellation, lpStats: LpStatsSink?): BoundedRouting {
    if (hasFiniteIntegerRanges()) return BoundedRouting.Proved(this)
    val closed = when (val outcome = closeOpenBounds(cancellation, lpStats)) {
        OpenPresolveResult.Refuted -> return BoundedRouting.Refuted
        is OpenPresolveResult.Tightened -> outcome
    }
    val spec = closed.spec
    // State the proved range as each column's value set, once every side is closed. A source that declared
    // bounds alone leaves `SourceIntDomains` with nothing stated, and the finite lane reads declared value
    // sets throughout — the pre-bake root LP check reaches one before anything has baked. A column still
    // open has no value set to state, so a partial closure travels as the bounds it proved.
    return BoundedRouting.Proved(
        if (spec.hasFiniteIntegerRanges()) spec.withIntDomains(spec.finiteIntDomains()) else spec,
    )
}

/** What bounding a model's open sides established: the model to route, or that there is nothing to route. */
private sealed interface BoundedRouting {
    /** The model as far as bounding could prove it, which is the model the lane is chosen from. */
    class Proved(val problem: Problem) : BoundedRouting

    /** Bounding refuted the model over its open ranges. */
    data object Refuted : BoundedRouting
}

/**
 * Inspect finite and linear component ownership without selecting a source invocation.
 *
 * This inspection prefers finite ownership for bounded integer declarations and performs no source
 * bound closure. [SourceSetup.prepare] selects an operation's route with source deductions, objective
 * precision and polynomial composition; this component view does not replace that setup.
 */
fun Problem.sourceRoute(): ProblemPipeline = componentPlan(preferFinite = hasFiniteIntegerRanges()).theoryPipeline
