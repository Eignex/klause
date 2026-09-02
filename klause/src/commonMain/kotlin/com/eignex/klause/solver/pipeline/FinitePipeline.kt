package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveStats
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
    /** Optional budget allocated to the presolve phase. */
    val presolveBudget: PresolveBudget? = null,
    /**
     * Component ownership selected from the untransformed [problem] during routing.
     *
     * Preparation re-selects it whenever a source pass rewrote factors, so this is the plan for the model
     * as the frontend handed it over, not necessarily the one the engine is built from.
     */
    val componentPlan: ComponentPlan = problem.componentPlan(preferFinite = true),
)

/** The finite model handed from policy to a concrete search engine. */
class FinitePipelinePreparation(
    /** Prepared model, or the canonical model when presolve returned a terminal infeasibility. */
    val problem: Problem,
    /** Objective re-fitted to [problem], or null for satisfiability. */
    val objective: LinearObjective?,
    /** Lifts a prepared-model assignment to the source model. */
    val reconstruct: (Sample) -> Sample,
    /** Presolve statistics when preparation changed the model. */
    val presolve: PresolveStats?,
    /** Time spent baking the source problem before this preparation began. */
    val constructionBakeElapsed: Duration = Duration.ZERO,
) {
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
        // A source rewrite moves factor ownership with the factors, so the routing plan no longer indexes
        // the model the engine is built from; an untouched model keeps the plan routing already built.
        val plan = if (prepared.changed) {
            prepared.problem.componentPlan(preferFinite = true)
        } else {
            request.componentPlan
        }
        plan.requireFullFiniteProjection(prepared.problem)
        val objective = prepared.objective ?: request.objective
        val outcome = PresolvePipeline.run(
            prepared,
            objective,
            config,
            request.solutionSetSensitive,
            request.cancellation,
        )
        val finiteModel = if (outcome.stats.infeasible) {
            outcome.problem
        } else if (outcome.changed) {
            outcome.problem.bake(request.cancellation)
        } else {
            request.problem.bake(request.cancellation)
        }
        return FinitePipelinePreparation(
            problem = finiteModel,
            objective = outcome.objective ?: objective,
            reconstruct = outcome.reconstruct,
            presolve = outcome.stats.takeIf { outcome.changed },
            constructionBakeElapsed = (request.problem as? BakedProblem)?.bakeElapsed ?: Duration.ZERO,
        )
    }
}
