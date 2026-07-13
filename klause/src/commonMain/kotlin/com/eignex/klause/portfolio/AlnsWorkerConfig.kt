package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.meta.alns.Alns
import com.eignex.klause.meta.alns.BacktrackRepair
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent

/**
 * A portfolio arm running hybrid ALNS with CP repair (#644): an outer destroy/repair loop over the
 * incumbent, each freed neighbourhood repaired by a bounded backtrack LCG+LP solve under the pinned
 * complement ([BacktrackRepair]) — full propagation, clause learning, and LP bounding on the fragment,
 * unlike a pure-LS repair. An LS-class engine: it optimises anytime and returns
 * [com.eignex.klause.solver.result.MinimizeResult.BestFound], never claiming completeness, so it fits
 * the local-search track. Composed last and only on a COP (it needs an objective to optimise).
 */
internal class AlnsWorkerConfig : WorkerConfig {
    override val label: String get() = "alns-cp"

    override fun materialize(
        problem: Problem,
        index: Int,
        armId: Int,
        seed: Long,
        lsLambda: Double,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        pools: SharedPools?,
    ): PortfolioWorker {
        val workerLabel = "alns/$label"
        val alns = Alns(
            inner = LocalSearchSolver(problem),
            repairOperators = BacktrackRepair.Defaults,
            backtrack = BacktrackSolver(problem),
            backtrackParams = BacktrackParams(randomSeed = seed + index),
        )
        val params = LocalSearchParams(
            randomSeed = seed + index,
            costShaping = CostShaping.Linear(lambda = lsLambda),
            lsObjective = lsObjective,
            onEvent = onEvent?.let { sink -> { e -> sink(workerLabel, e) } },
        )
        return PortfolioWorker.of(
            workerLabel,
            armId,
            alns.session(),
            params,
            objective = objective,
            withWarmStart = { p, sample -> p.copy(initialAssignment = sample) },
        )
    }
}
