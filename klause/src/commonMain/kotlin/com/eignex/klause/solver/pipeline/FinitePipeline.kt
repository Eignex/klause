package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bakeFiniteBounds
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
    /** Component ownership selected from [problem] during routing. */
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
        request.componentPlan.requireFullFiniteProjection(request.problem)
        val config = if (request.engine.pureLocalSearch && !request.explicitPresolveConfig) {
            request.presolveConfig.forLocalSearch()
        } else {
            request.presolveConfig
        }
        val outcome = PresolvePipeline.run(
            request.problem,
            request.objective,
            config,
            request.solutionSetSensitive,
            request.cancellation,
            request.presolveBudget,
        )
        val prepared = if (outcome.stats.infeasible) {
            outcome.problem
        } else if (outcome.changed) {
            outcome.problem.bakeFiniteBounds(request.cancellation)
        } else {
            request.problem.bakeFiniteBounds(request.cancellation)
        }
        return FinitePipelinePreparation(
            problem = prepared,
            objective = outcome.objective ?: request.objective,
            reconstruct = outcome.reconstruct,
            presolve = outcome.stats.takeIf { outcome.changed },
            constructionBakeElapsed = (request.problem as? BakedProblem)?.bakeElapsed ?: Duration.ZERO,
        )
    }
}
