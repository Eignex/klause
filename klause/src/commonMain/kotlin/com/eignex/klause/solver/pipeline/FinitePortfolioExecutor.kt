package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent

/** Materializes [scenario] over [problem] and schedules it on one lane per core. */
fun FinitePipeline.portfolioExecutor(
    problem: BakedProblem,
    scenario: PortfolioScenario,
    objective: LinearObjective?,
    lsObjective: IncrementalObjective?,
    definitionalSweep: DefinitionalSweep?,
    onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
): PortfolioExecutor {
    val workers = PortfolioBuilder.build(
        problem,
        scenario,
        objective = objective,
        lsObjective = lsObjective,
        definitionalSweep = definitionalSweep,
        onEvent = onEvent,
    )
    return Portfolio.thompson(workers, lanes = scenario.cores, baseSliceWork = scenario.sliceWork)
}

/** Creates the fixed finite-domain solver over [problem]. */
fun FinitePipeline.backtrackSolver(problem: BakedProblem): BacktrackSolver = BacktrackSolver(problem)
