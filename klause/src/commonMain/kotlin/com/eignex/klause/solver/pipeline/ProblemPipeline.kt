package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.theory.qflra.supportsExactLra

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
    /** A fully bounded source model materialized for finite-domain search. */
    data class Finite(
        /** The materialized finite-domain model. */
        val problem: BakedProblem,
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
}

/**
 * Select a pipeline once for this source model.
 *
 * Finite source ranges materialize their declared bounds for CP. Open models carry a complete theory
 * request when one exists; callers only need to render its uniform assignment surface. A frontend that
 * supports exact pure-real solving sets [routePureRealToTheory] to select that lane instead of finite CP.
 */
fun Problem.pipelineRoute(
    objective: LinearObjective? = null,
    maximize: Boolean = false,
    routePureRealToTheory: Boolean = false,
): SourceProblemRoute {
    val finiteIntegerRanges = (0 until numIntVars).all { intBounds.hasLower(it) && intBounds.hasUpper(it) }
    val preferFinite = finiteIntegerRanges && (!routePureRealToTheory || !supportsExactLra())
    val plan = componentPlan(preferFinite)
    if (plan.theoryPipeline == ProblemPipeline.FINITE_CP) {
        if (this is BakedProblem) return SourceProblemRoute.Finite(this, plan)
        val domains = plan.cpIntVars.associateWith { variable ->
            intDomainOrNull(variable) ?: IntDomain(intBounds.lower(variable), intBounds.upper(variable))
        }
        return SourceProblemRoute.Finite(plan.finiteProjection(this, domains), plan)
    }
    val request = OpenTheoryRequest(this, objective, maximize, plan)
    return if (request.route == ProblemPipeline.UNSUPPORTED_OPEN || request.route == ProblemPipeline.FINITE_CP) {
        SourceProblemRoute.UnsupportedOpen(request.componentPlan.unplaceable)
    } else {
        SourceProblemRoute.OpenTheory(request)
    }
}

/**
 * The route a frontend hands to the solver for one source model.
 *
 * A fully bounded model stays on the finite route: that is a frontend policy about which engine owns a
 * finite domain, not a statement that no theory could decide it. Anything with an open integer side is
 * classified by [componentPlan], which reads column and factor ownership rather than demanding one
 * theory cover the whole model.
 */
fun Problem.sourceRoute(): ProblemPipeline {
    val finite = (0 until numIntVars).all { intBounds.hasLower(it) && intBounds.hasUpper(it) }
    return componentPlan(preferFinite = finite).theoryPipeline
}

/**
 * A factor some lane other than CP can hold, so CP need not own the columns it reads.
 *
 * The one rule the plan reads: [variablePartition] marks a column search-required from it, [componentPlan]
 * assigns factor ownership from it, and an open column it marks has no owner at all — the model's verdict.
 */
internal fun Factor.isTheoryOwnable(hasRealColumns: Boolean): Boolean =
    integerTheoryOwnable || (hasRealColumns && exactTheoryOwnable)
