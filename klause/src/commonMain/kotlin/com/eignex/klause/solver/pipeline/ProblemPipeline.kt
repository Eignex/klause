package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.solver.FactorProjectionProviders
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
     * This is a frontend policy, not variable ownership: [Problem.componentPlan] may still select a
     * complete arithmetic theory for the same finite source model when no finite-domain factor is present.
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
 * Select a pipeline once for this source model.
 *
 * Finite source ranges defer their root-propagated CP projection. Open models carry a complete theory
 * request when one exists; callers only need to render its uniform assignment surface. A frontend that
 * supports exact pure-real solving sets [routePureRealToTheory] to select that lane instead of finite CP.
 * [routeLinearToTheory] also offers bounded mixed integer/real linear models to the exact theory.
 *
 * A model the source left open is first offered to [proveBounded], because what the lane must be chosen
 * from is the model as it can be proved, not as it was written. [boundCancellation] bounds that proof.
 */
fun Problem.pipelineRoute(
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    routePureRealToTheory: Boolean = false,
    boundCancellation: Cancellation = Cancellation.Never,
    routeLinearToTheory: Boolean = false,
): SourceProblemRoute = pipelineRouteObserved(
    objective,
    maximize,
    routePureRealToTheory,
    boundCancellation,
    onLpStats = null,
    routeLinearToTheory = routeLinearToTheory,
)

/** Select a pipeline and report the LP work performed while proving bounds for that selection. */
fun Problem.pipelineRoute(
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    routePureRealToTheory: Boolean = false,
    boundCancellation: Cancellation = Cancellation.Never,
    routeLinearToTheory: Boolean = false,
    onLpStats: (LpStats) -> Unit,
): SourceProblemRoute = pipelineRouteObserved(
    objective,
    maximize,
    routePureRealToTheory,
    boundCancellation,
    onLpStats,
    routeLinearToTheory,
)

/** Select the shared source route with per-invocation factor projection providers. */
fun Problem.pipelineRoute(
    providers: FactorProjectionProviders,
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    routePureRealToTheory: Boolean = false,
    boundCancellation: Cancellation = Cancellation.Never,
    routeLinearToTheory: Boolean = false,
    onLpStats: ((LpStats) -> Unit)? = null,
): SourceProblemRoute = pipelineRouteObserved(objective, maximize, routePureRealToTheory,
    boundCancellation, onLpStats, routeLinearToTheory, providers)

private fun Problem.pipelineRouteObserved(
    objective: LinearObjective?,
    maximize: Boolean,
    routePureRealToTheory: Boolean,
    boundCancellation: Cancellation,
    onLpStats: ((LpStats) -> Unit)?,
    routeLinearToTheory: Boolean,
    providers: FactorProjectionProviders = FactorProjectionProviders.Default,
): SourceProblemRoute {
    val source = prepareExecutionPlan(providers)
    val initial = objective?.let { source.withObjective(it, maximize) } ?: source
    if (initial.kind == PreparedExecutionKind.POLYNOMIAL) {
        return SourceProblemRoute.PolynomialTheory(initial.polynomialRequest(), objective, maximize)
    }
    if (initial.kind == PreparedExecutionKind.DECLINED) {
        return SourceProblemRoute.UnsupportedOpen(null, this, initial)
    }
    val lpStats = onLpStats?.let { LpStatsSink(LpRoute.STANDALONE) }
    val bounded = proveBounded(boundCancellation, lpStats)
    if (lpStats != null) onLpStats(lpStats.snapshot())
    val routed = when (bounded) {
        BoundedRouting.Refuted -> return SourceProblemRoute.Refuted
        is BoundedRouting.Proved -> bounded.problem
    }
    val finiteIntegerRanges = routed.hasFiniteIntegerRanges()
    val projections = requireNotNull(source.factorProjections).forEngineView(routed)
    val arithmetic = routed.prepareExecutionPlan(this, objective, maximize,
        preferFinite = false, providers, projections)
    val arithmeticRoute = arithmetic.componentPlan?.theoryPipeline
    val preferExact = routeLinearToTheory &&
        (arithmeticRoute == ProblemPipeline.EXACT_LRA || arithmeticRoute == ProblemPipeline.EXACT_LIRA)
    val preferFinite = finiteIntegerRanges && !preferExact &&
        (!routePureRealToTheory || arithmeticRoute != ProblemPipeline.EXACT_LRA)
    val prepared = if (preferFinite) routed.prepareExecutionPlan(this, objective, maximize,
        preferFinite = true, providers, projections) else arithmetic
    val plan = requireNotNull(prepared.componentPlan)
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
 * model being handed to the exact theory, which cannot use the proof and, on the `gen-ip*` and `enlight*`
 * families, spends the whole budget inside one exact reduction.
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
 * Finite and linear route classification. Use [pipelineRoute] for polynomial composition.
 *
 * A fully bounded model stays on the finite route: that is a frontend policy about which engine owns a
 * finite domain, not a statement that no theory could decide it. Anything with an open integer side is
 * classified by [componentPlan], which reads column and factor ownership rather than demanding one
 * theory cover the whole model.
 */
fun Problem.sourceRoute(): ProblemPipeline = componentPlan(preferFinite = hasFiniteIntegerRanges()).theoryPipeline
