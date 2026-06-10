package com.eignex.klause.portfolio

import com.eignex.klause.solver.DefinitionalSweep
import com.eignex.klause.solver.IncrementalObjective
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SearchEvent

/**
 * Materialises the arms a [PortfolioScenario] composes to into runnable [PortfolioWorker]s — the
 * single construction path every portfolio flows through, whatever the scenario (threads × kind ×
 * engine). The *policy* (which arms, ordering, mixed split) lives in [PortfolioComposition]; the
 * per-arm construction (which solver, objective form, bound / warm-start seam) lives in each
 * [WorkerConfig.materialize]. This builder is the thin glue: `compose` → `map { it.materialize(…) }`,
 * with no engine-specific switch.
 *
 * The returned [PortfolioWorker] list is the shared, executor-agnostic unit: wrap it in a parallel
 * [Portfolio] (`scenario.threads > 1`) or a single-core bandit-scheduled [SequentialPortfolio]
 * (`scenario.threads == 1`). The list is identical either way — only the executor differs.
 */
object PortfolioBuilder {
    /**
     * Compose [scenario] and materialise its arms over [problem].
     *
     * [objective] is the canonical [LinearObjective] every optimising worker minimises (null for a
     * satisfaction-only portfolio). [lsObjective] is the optional per-move gradient view of the
     * same objective for the local-search workers (see `LocalSearchParams.lsObjective`); backtrack
     * workers ignore it.
     *
     * [definitionalSweep] is threaded into every LS worker (per-move invariants, #153). [onEvent]
     * threads the [SearchEvent] seam through to every worker tagged with its label; workers run
     * concurrently under a parallel [Portfolio], so the listener must be thread-safe and cheap.
     */
    fun build(
        problem: Problem,
        scenario: PortfolioScenario,
        objective: LinearObjective? = null,
        lsObjective: IncrementalObjective? = null,
        definitionalSweep: DefinitionalSweep? = null,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)? = null,
    ): List<PortfolioWorker> = materialize(
        problem,
        PortfolioComposition.compose(scenario),
        scenario.seed,
        scenario.lsLambda,
        objective,
        lsObjective,
        definitionalSweep,
        onEvent,
    )

    /**
     * Override entry for the per-worker credit campaign (#9): materialise an **explicit** arm mix —
     * the LS configs named in [lsLabels] (or the whole pool for `["all"]`) plus [backtrackWorkers]
     * backtrack arms of [kind] — bypassing [PortfolioComposition] so the campaign can attribute
     * credit to an arbitrary composition. All other wiring matches [build].
     */
    fun buildExplicit(
        problem: Problem,
        lsLabels: List<String>,
        backtrackWorkers: Int,
        kind: Kind,
        seed: Long = 0L,
        lsLambda: Double = 1.0,
        objective: LinearObjective? = null,
        lsObjective: IncrementalObjective? = null,
        definitionalSweep: DefinitionalSweep? = null,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)? = null,
    ): List<PortfolioWorker> {
        require(backtrackWorkers >= 0) { "backtrackWorkers must be ≥ 0" }
        val lsConfigs = if (lsLabels == listOf("all")) {
            LocalSearchWorkerConfig.pool()
        } else {
            lsLabels.map { LocalSearchWorkerConfig.byLabel(it) }
        }
        val arms = buildList<WorkerConfig> {
            addAll(lsConfigs)
            if (backtrackWorkers > 0) addAll(BacktrackWorkerConfig.diverse(kind, backtrackWorkers))
        }
        return materialize(problem, arms, seed, lsLambda, objective, lsObjective, definitionalSweep, onEvent)
    }

    /** Materialise each composed arm via its own [WorkerConfig.materialize] — the shared body of
     *  [build] and [buildExplicit]. The arm index offsets the seed (and numbers backtrack labels). */
    private fun materialize(
        problem: Problem,
        arms: List<WorkerConfig>,
        seed: Long,
        lsLambda: Double,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
    ): List<PortfolioWorker> {
        val workers = arms.mapIndexed { i, config ->
            config.materialize(problem, i, seed, lsLambda, objective, lsObjective, definitionalSweep, onEvent)
        }
        check(workers.isNotEmpty()) { "portfolio produced no workers" }
        return workers
    }
}
