package com.eignex.klause.solver.pipeline

import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.util.Cancellation

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
)

/** The finite model handed from policy to a concrete search engine. */
class FinitePipelinePreparation(
    /** Prepared model to hand to the selected engine. */
    val problem: Problem,
    /** Objective re-fitted to [problem], or null for satisfiability. */
    val objective: LinearObjective?,
    /** Lifts a prepared-model assignment to the source model. */
    val reconstruct: (Sample) -> Sample,
    /** Presolve statistics when preparation changed the model. */
    val presolve: PresolveStats?,
)

/** Owns finite-route presolve policy before a concrete engine is constructed. */
object FinitePipeline {
    /** Apply finite-route presolve policy and return the model ready for engine construction. */
    fun prepare(request: FinitePipelineRequest): FinitePipelinePreparation {
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
        return FinitePipelinePreparation(
            problem = if (outcome.changed) outcome.problem else request.problem,
            objective = outcome.objective ?: request.objective,
            reconstruct = outcome.reconstruct,
            presolve = outcome.stats.takeIf { outcome.changed },
        )
    }
}
