package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.hasActivity
import com.eignex.klause.solver.withRealDifferenceSystem
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration

/** Inputs the finite orchestration layer needs before selecting and constructing an engine. */
class FinitePipelineRequest(
    /** Source finite model to prepare. */
    val problem: Problem,
    /** Finite route selected by the caller. */
    val engine: FiniteEngine,
    /** Objective to preserve through presolve, or null for satisfiability. */
    val objective: LinearObjective? = null,
    /** Presolve configuration requested by the caller. */
    val presolveConfig: PresolveConfig = PresolveConfig.DEFAULT,
    /** True when the caller explicitly selected [presolveConfig], preserving every enabled pass. */
    val explicitPresolveConfig: Boolean = false,
    /** Whether the caller needs the exact solution set rather than only one solution or optimum. */
    val solutionSetSensitive: Boolean = false,
    /** Cancellation shared by preparation and the eventual solve. */
    val cancellation: Cancellation = Cancellation.Never,
    /**
     * The run's own stop, without the presolve allowance [cancellation] may carry. The bake that hands the
     * prepared model to the engine reads only this, so a spent allowance never leaves the engine a model
     * baked part way.
     */
    val solveCancellation: Cancellation = cancellation,
    /** Optional budget allocated to the presolve phase. */
    val presolveBudget: PresolveBudget? = null,
    /** Entering-column policy used by zero-objective LP solves during finite presolve. */
    val zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    /** Tie-breaking seed used by LP pricing during finite presolve. */
    val randomSeed: Long? = null,
    /**
     * Component ownership selected from the untransformed [problem] during routing.
     *
     * Preparation re-selects it whenever a source pass rewrote factors, so this is the plan for the model
     * as the frontend handed it over, not necessarily the one the engine is built from.
     */
    componentPlan: ComponentPlan? = null,
    /** Shared source plan from routing, or captured here for direct finite callers. */
    val preparedPlan: PreparedSourcePlan = if (componentPlan == null) {
        problem.prepareExecutionPlan(objective, preferFinite = true)
    } else {
        problem.preparedWithComponents(componentPlan, objective = objective)
    },
) {
    /** Finite projection data selected by [preparedPlan]. */
    val componentPlan: ComponentPlan = requireNotNull(preparedPlan.execution.finiteLinear) {
        "finite preparation requires a finite/linear source plan"
    }.ownership

    init {
        require(preparedPlan.model === problem) { "finite request and plan must retain the same canonical model" }
        require(componentPlan == null || componentPlan === this.componentPlan) {
            "finite request and plan must retain the same component ownership"
        }
    }
}

/** The finite model handed from policy to a concrete search engine. */
class FinitePipelinePreparation(
    /** Prepared model, or the canonical model when presolve returned a terminal infeasibility. */
    val problem: Problem,
    /** Objective re-fitted to [problem], or null for satisfiability. */
    val objective: LinearObjective?,
    /** Lifts a prepared-model assignment to the source model. */
    val reconstruct: (Sample) -> Sample,
    /** Presolve statistics, including LP work from a no-op preparation. */
    val presolve: PresolveStats?,
    /** Time spent baking the source problem before this preparation began. */
    val constructionBakeElapsed: Duration = Duration.ZERO,
) {
    internal var sourceMapping: SourceMapping? = null
    internal var executionPlan: PreparedSourcePlan? = null

    internal fun executableProblem(): BakedProblem = requireNotNull(problem as? BakedProblem) {
        "a nonterminal finite preparation must produce a BakedProblem"
    }
}

/** Owns finite-route presolve policy before a concrete engine is constructed. */
object FinitePipeline {
    /** Portfolio composition selected for a finite route that does not use the fixed search annotation. */
    fun portfolioMix(engine: FiniteEngine): EngineMix = when (engine) {
        FiniteEngine.FIXED -> error("the fixed route does not execute through a portfolio")
        FiniteEngine.BACKTRACK -> EngineMix.BACKTRACK
        FiniteEngine.LOCAL_SEARCH -> EngineMix.LOCAL_SEARCH
        FiniteEngine.MIXED -> EngineMix.MIXED
        FiniteEngine.ALNS -> EngineMix.ALNS
    }

    /** Apply finite-route presolve policy and return the model ready for engine construction. */
    fun prepare(request: FinitePipelineRequest): FinitePipelinePreparation {
        val config = if (request.engine.pureLocalSearch && !request.explicitPresolveConfig) {
            request.presolveConfig.forLocalSearch()
        } else {
            request.presolveConfig
        }
        // Source-safe presolve is the one phase before the plan, shared with the open lane. Running it
        // here and handing the result on is what keeps [PresolvePipeline.run] from repeating it.
        val prepared = PresolvePipeline.prepareSource(
            request.problem,
            config,
            request.objective,
            request.solutionSetSensitive,
            request.cancellation,
            request.presolveBudget,
        )
        // A source rewrite moves factor ownership with the factors, so the routing plan does not index the
        // model the engine is built from; an untouched model keeps the plan routing already built.
        val sourcePlan = if (prepared.changed) {
            prepared.problem.prepareExecutionPlan(
                original = requireNotNull(request.preparedPlan.sourceModel),
                objective = request.preparedPlan.workingLinearObjective,
                maximize = request.preparedPlan.maximize,
                preferFinite = true,
                providers = request.preparedPlan.factorProjections?.providers ?: FactorProjectionProviders.Default,
                cancellation = request.solveCancellation,
            ).retainingLinearSource(request.preparedPlan)
        } else {
            request.preparedPlan
        }
        if (sourcePlan.decline == PreparedPlanDecline.RESOURCE) {
            return FinitePipelinePreparation(
                prepared.problem,
                prepared.objective ?: request.objective,
                { prepared.mapping.reconstructFrom(prepared.problem, it) },
                prepared.stats,
            ).also {
                it.sourceMapping = prepared.mapping
                it.executionPlan = sourcePlan
            }
        }
        val plan = requireNotNull(sourcePlan.componentPlan)
        plan.requireFullFiniteProjection(prepared.problem)
        val objective = prepared.objective ?: request.objective
        val outcome = PresolvePipeline.run(
            prepared,
            objective,
            config,
            request.solutionSetSensitive,
            request.cancellation,
            request.zeroObjectivePricing,
            request.randomSeed,
            plan.factorProjections,
        )
        val bakedModel = if (outcome.stats.infeasible) {
            outcome.problem
        } else {
            val model = if (outcome.changed) outcome.problem else request.problem
            val projections = (model as? BakedProblem)?.factorProjections
                ?.takeIf { it.providers === plan.factorProjections.providers }
                ?: plan.factorProjections.forEngineView(model)
            model.bake(projections, request.solveCancellation)
        }
        val finiteModel = if (!outcome.stats.infeasible && bakedModel is BakedProblem) {
            bakedModel.withRealDifferenceSystem(request.solveCancellation)
        } else bakedModel
        val mapping = if (finiteModel === bakedModel) outcome.mapping else outcome.mapping.then(
            SourceMapping(outcome.mapping.target, finiteModel, TransformationGuarantees.IDENTITY),
        )
        val sameCoordinatesAndFactors = finiteModel.numBoolVars == request.problem.numBoolVars &&
            finiteModel.numIntVars == request.problem.numIntVars &&
            finiteModel.numRealVars == request.problem.numRealVars &&
            finiteModel.numFactors == request.problem.numFactors &&
            finiteModel.factors.indices.all { finiteModel.factors[it] === request.problem.factors[it] }
        val executionPlan = if (outcome.stats.infeasible || sameCoordinatesAndFactors) {
            request.preparedPlan
        } else {
            val model = requireNotNull(finiteModel as? BakedProblem)
            val providers = request.preparedPlan.factorProjections?.providers ?: FactorProjectionProviders.Default
            val projections = model.factorProjections?.takeIf { it.providers === providers } ?: providers.prepare(model)
            model.prepareExecutionPlan(
                original = requireNotNull(request.preparedPlan.sourceModel),
                objective = request.preparedPlan.workingLinearObjective,
                maximize = request.preparedPlan.maximize,
                preferFinite = true,
                providers = providers,
                projections = projections,
                cancellation = request.solveCancellation,
            ).retainingLinearSource(request.preparedPlan)
        }
        return FinitePipelinePreparation(
            problem = finiteModel,
            objective = outcome.objective ?: objective,
            reconstruct = if (mapping === outcome.mapping) outcome.reconstruct
                else { sample -> mapping.reconstructFrom(finiteModel, sample) },
            presolve = outcome.stats.takeIf { outcome.changed || it.infeasible || it.lpStats.hasActivity() },
            constructionBakeElapsed = (request.problem as? BakedProblem)?.bakeElapsed ?: Duration.ZERO,
        ).also {
            it.sourceMapping = mapping
            it.executionPlan = executionPlan
        }
    }
}
