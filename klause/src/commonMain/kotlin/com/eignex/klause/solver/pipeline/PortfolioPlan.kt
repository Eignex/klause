package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario

/** Inputs for resolving a finite portfolio route into an execution or diagnostic plan. */
class PortfolioPlanRequest(
    /** Finite route whose portfolio composition is being planned. */
    val engine: FiniteEngine,
    /** Whether the model carries a minimization objective. */
    val optimize: Boolean,
    /** Worker count selected by the frontend. */
    val cores: Int,
    /** Repeatable engine tuning parameters supplied by the frontend. */
    val engineParams: List<String>,
    /** Optional seed supplied by the frontend. */
    val randomSeed: Long?,
    /** Default arm-pool size when the parameters do not override it. */
    val defaultArms: Int,
    /** Ceiling applied to LP-enabled backtrack arms. */
    val lpCeiling: LpConfig,
    /** One invocation-wide backtrack node allowance, if selected. */
    val nodeBudget: NodeBudget?,
    /** Optional model search-annotation arm. */
    val annotationArm: BacktrackParams?,
)

/** A resolved finite portfolio route, before a problem is materialized into workers. */
sealed class PortfolioPlan {
    /** Construct this scenario and execute it through the finite portfolio executor. */
    class Execute(
        /** Scenario to execute through the finite portfolio executor. */
        val scenario: PortfolioScenario,
    ) : PortfolioPlan()

    /** Render the resolved local-search recipes instead of executing them. */
    class LocalSearchDryRun(
        /** Resolved local-search recipes, or `null` for the curated catalog. */
        val pool: List<() -> LocalSearchRecipe>?,
    ) : PortfolioPlan()

    /** Render the resolved backtrack recipes instead of executing them. */
    class BacktrackDryRun(
        /** Resolved backtrack recipes, or `null` for the curated catalog. */
        val pool: List<() -> BacktrackRecipe>?,
        /** Problem kind used to resolve the backtrack recipes. */
        val kind: Kind,
    ) : PortfolioPlan()
}

/** Resolve portfolio policy without constructing a problem-specific engine or rendering frontend output. */
fun FinitePipeline.planPortfolio(request: PortfolioPlanRequest): PortfolioPlan {
    val mix = portfolioMix(request.engine)
    val params = EngineParams(request.engineParams)
    val lsResolution = if (mix != EngineMix.BACKTRACK) resolveLocalSearchRecipes(params) else LsResolution(null, false)
    if (lsResolution.dryRunSolver) return PortfolioPlan.LocalSearchDryRun(lsResolution.pool)

    val kind = if (request.optimize) Kind.COP else Kind.CSP
    val resolvedBtPool = if (mix != EngineMix.LOCAL_SEARCH) resolveBtRecipes(params, kind) else null
    val btPool = if (request.nodeBudget != null && mix != EngineMix.LOCAL_SEARCH) {
        withNodeBudget(resolvedBtPool, kind, request.nodeBudget)
    } else {
        resolvedBtPool
    }
    if (mix == EngineMix.BACKTRACK && params.bool("dry-run-solver") == true) {
        return PortfolioPlan.BacktrackDryRun(btPool, kind)
    }

    return PortfolioPlan.Execute(
        buildPortfolioScenario(
            p = params,
            fallbackSeed = request.randomSeed,
            cores = request.cores,
            kind = kind,
            defaultEngine = mix,
            defaultArms = lsResolution.forceArms ?: request.defaultArms,
            lpCeiling = request.lpCeiling,
            lsPool = lsResolution.pool,
            btPool = btPool,
            annotationArm = request.annotationArm?.copy(nodeBudget = request.nodeBudget),
        ),
    )
}
