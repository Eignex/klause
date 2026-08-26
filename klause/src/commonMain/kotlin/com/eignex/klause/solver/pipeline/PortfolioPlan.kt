package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation

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
    class Execute(val scenario: PortfolioScenario) : PortfolioPlan()

    /** Render the resolved local-search recipes instead of executing them. */
    class LocalSearchDryRun(val pool: List<() -> LocalSearchRecipe>?) : PortfolioPlan()

    /** Render the resolved backtrack recipes instead of executing them. */
    class BacktrackDryRun(
        val pool: List<() -> BacktrackRecipe>?,
        val kind: Kind,
    ) : PortfolioPlan()
}

/** Inputs for resolving the single fixed backtrack route into solver parameters. */
class FixedBacktrackPlanRequest(
    /** Optional search annotation compiled from the source model. */
    val annotatedParams: BacktrackParams?,
    /** Repeatable engine tuning parameters supplied by the frontend. */
    val engineParams: List<String>,
    /** Optional seed supplied by the frontend. */
    val randomSeed: Long?,
    /** Cancellation shared by the solve route. */
    val cancellation: Cancellation,
    /** One invocation-wide backtrack node allowance, if selected. */
    val nodeBudget: NodeBudget?,
    /** Advisory wall-clock budget for LP subcomponents. */
    val solveBudgetMillis: Long?,
    /** LP configuration selected by the frontend. */
    val lpConfig: LpConfig,
    /** Optional engine event sink supplied by the frontend. */
    val onEvent: ((SearchEvent) -> Unit)?,
)

/** Resolved parameters for the annotation-following finite backtrack route. */
class FixedBacktrackPlan(
    /** Parameters passed to the fixed backtrack solver. */
    val params: BacktrackParams,
    /** Whether the frontend should render parameters rather than execute them. */
    val dryRun: Boolean,
)

/** Resolve fixed-route policy without constructing a problem-specific solver or rendering frontend output. */
fun FinitePipeline.planFixedBacktrack(request: FixedBacktrackPlanRequest): FixedBacktrackPlan {
    val base = request.annotatedParams ?: BacktrackPresets.conflictDriven()
    val engineParams = EngineParams(request.engineParams)
    val dryRun = engineParams.bool("dry-run-solver") ?: false
    val params = applyBacktrackParams(
        base.copy(
            randomSeed = request.randomSeed ?: base.randomSeed,
            cancellation = request.cancellation,
            nodeBudget = request.nodeBudget,
            solveBudgetMillis = request.solveBudgetMillis,
            propagationCancelFloor = if (request.solveBudgetMillis != null) 0 else base.propagationCancelFloor,
            onEvent = request.onEvent,
            lpConfig = request.lpConfig,
        ),
        engineParams,
    )
    return FixedBacktrackPlan(params, dryRun)
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
