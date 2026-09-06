package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.OpenPresolveResult
import com.eignex.klause.presolve.closeOpenBounds
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.theory.qflra.supportsExactLra
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
    /** A fully bounded source model selected for deferred finite-domain preparation. */
    data class Finite(
        /** Canonical logical model retained until deferred finite preparation. */
        val problem: Problem,
        /** Component ownership selected from the same canonical source model. */
        val componentPlan: ComponentPlan,
    ) : SourceProblemRoute

    /** A supported open source model with its selected theory request. */
    data class OpenTheory(
        /** The complete open-theory request. */
        val request: OpenTheoryRequest,
    ) : SourceProblemRoute

    /** An open source model for which no complete route exists. */
    data class UnsupportedOpen(
        /** The column and factor that prevented routing, when one specific column caused the refusal. */
        val unplaceable: UnplaceableColumn?,
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
 *
 * A model the source left open is first offered to [proveBounded], because what the lane must be chosen
 * from is the model as it can be proved, not as it was written. [boundCancellation] bounds that proof.
 */
fun Problem.pipelineRoute(
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    routePureRealToTheory: Boolean = false,
    boundCancellation: Cancellation = Cancellation.Never,
): SourceProblemRoute {
    val routed = when (val bounded = proveBounded(boundCancellation)) {
        BoundedRouting.Refuted -> return SourceProblemRoute.Refuted
        is BoundedRouting.Proved -> bounded.problem
    }
    val finiteIntegerRanges = routed.hasFiniteIntegerRanges()
    val preferFinite = finiteIntegerRanges && (!routePureRealToTheory || !routed.supportsExactLra())
    val plan = routed.componentPlan(preferFinite)
    if (plan.theoryPipeline == ProblemPipeline.FINITE_CP) {
        return SourceProblemRoute.Finite(routed, plan)
    }
    val request = OpenTheoryRequest(routed, objective, maximize, plan)
    return if (request.route == ProblemPipeline.UNSUPPORTED_OPEN || request.route == ProblemPipeline.FINITE_CP) {
        SourceProblemRoute.UnsupportedOpen(request.componentPlan.unplaceable)
    } else {
        SourceProblemRoute.OpenTheory(request)
    }
}

/** Whether every integer column states both of its sides. */
private fun Problem.hasFiniteIntegerRanges(): Boolean =
    (0 until numIntVars).all { intBounds.hasLower(it) && intBounds.hasUpper(it) }

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
private fun Problem.proveBounded(cancellation: Cancellation): BoundedRouting {
    if (hasFiniteIntegerRanges()) return BoundedRouting.Proved(this)
    val closed = when (val outcome = closeOpenBounds(cancellation)) {
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
 * The route a frontend hands to the solver for one source model.
 *
 * A fully bounded model stays on the finite route: that is a frontend policy about which engine owns a
 * finite domain, not a statement that no theory could decide it. Anything with an open integer side is
 * classified by [componentPlan], which reads column and factor ownership rather than demanding one
 * theory cover the whole model.
 */
fun Problem.sourceRoute(): ProblemPipeline = componentPlan(preferFinite = hasFiniteIntegerRanges()).theoryPipeline

/**
 * A factor some lane other than CP can hold, so CP need not own the columns it reads.
 *
 * The one rule the plan reads: [variablePartition] marks a column search-required from it, [componentPlan]
 * assigns factor ownership from it, and an open column it marks has no owner at all — the model's verdict.
 */
internal fun Factor.isTheoryOwnable(hasRealColumns: Boolean): Boolean =
    integerTheoryOwnable || (hasRealColumns && exactTheoryOwnable)
