package com.eignex.klause.solver.pipeline

import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.SequentialPortfolio
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent

/** Materializes [scenario] over [problem] and selects its sequential or parallel executor. */
fun FinitePipeline.portfolioExecutor(
    problem: Problem,
    scenario: PortfolioScenario,
    objective: LinearObjective?,
    lsObjective: IncrementalObjective?,
    definitionalSweep: DefinitionalSweep?,
    onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
): PortfolioExecutor {
    val workers = PortfolioBuilder.build(
        problem.bake(),
        scenario,
        objective = objective,
        lsObjective = lsObjective,
        definitionalSweep = definitionalSweep,
        onEvent = onEvent,
    )
    return if (scenario.cores == 1) SequentialPortfolio.exp3(workers) else parallelPortfolio(workers)
}

internal expect fun parallelPortfolio(workers: List<PortfolioWorker>): PortfolioExecutor
