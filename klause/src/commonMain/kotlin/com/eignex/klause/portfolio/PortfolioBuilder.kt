package com.eignex.klause.portfolio

import com.eignex.klause.solver.DefinitionalSweep
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver

/**
 * Materialises the arms a [PortfolioScenario] composes to into runnable [PortfolioWorker]s — the
 * single construction path every portfolio flows through, whatever the scenario (threads × kind ×
 * engine). The *policy* (which arms, ordering, mixed split) lives entirely in
 * [PortfolioComposition]; this builder only turns the chosen [ArmRef]s into sessions + params,
 * wiring each engine's objective representation, bound seam, and warm-start seam.
 *
 * The returned [PortfolioWorker] list is the shared, executor-agnostic unit: wrap it in a parallel
 * [Portfolio] (`scenario.threads > 1`) or a single-core bandit-scheduled [SequentialPortfolio]
 * (`scenario.threads == 1`). The list is identical either way — only the executor differs — so the
 * same scenario produces the same arms regardless of how they are run.
 */
object PortfolioBuilder {
    /**
     * Compose [scenario] and materialise its arms over [problem].
     *
     * For optimisation the two objective representations differ per engine but agree on the scalar
     * bound (#63): [lsObjective] is the functional/gradient objective the local-search workers
     * descend, [linearObjective] is the [com.eignex.klause.solver.LinearObjective] the backtrack
     * workers bound-prune on. Either may be null: pass both null for a satisfaction-only portfolio
     * (`solve`), or just one if the model only provides that form (each engine falls back to the
     * other when its preferred representation is absent). When an objective is present the backtrack
     * workers get the shared objective-bound supplier; a pure CSP has no bound to prune on.
     *
     * [definitionalSweep] is threaded into every LS worker (per-move invariants, #153). [onEvent]
     * threads the [SearchEvent] seam through to every worker tagged with its label; workers run
     * concurrently under a parallel [Portfolio], so the listener must be thread-safe and cheap.
     */
    fun build(
        problem: Problem,
        scenario: PortfolioScenario,
        lsObjective: Objective? = null,
        linearObjective: Objective? = null,
        definitionalSweep: DefinitionalSweep? = null,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)? = null,
    ): List<PortfolioWorker> = materialize(
        problem,
        PortfolioComposition.compose(scenario),
        scenario.seed,
        scenario.lsLambda,
        lsObjective,
        linearObjective,
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
        lsObjective: Objective? = null,
        linearObjective: Objective? = null,
        definitionalSweep: DefinitionalSweep? = null,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)? = null,
    ): List<PortfolioWorker> {
        require(backtrackWorkers >= 0) { "backtrackWorkers must be ≥ 0" }
        val lsConfigs = if (lsLabels == listOf("all")) {
            LocalSearchWorkerConfig.pool()
        } else {
            lsLabels.map { LocalSearchWorkerConfig.byLabel(it) }
        }
        val arms = buildList<ArmRef> {
            lsConfigs.forEach { add(ArmRef.Ls(it)) }
            if (backtrackWorkers > 0) {
                BacktrackWorkerConfig.diverse(
                    kind,
                    backtrackWorkers,
                ).forEach { add(ArmRef.Bt(it)) }
            }
        }
        return materialize(problem, arms, seed, lsLambda, lsObjective, linearObjective, definitionalSweep, onEvent)
    }

    /** Turn composed [arms] into workers — the shared body of [build] and [buildExplicit]. */
    private fun materialize(
        problem: Problem,
        arms: List<ArmRef>,
        seed: Long,
        lsLambda: Double,
        lsObjective: Objective?,
        linearObjective: Objective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
    ): List<PortfolioWorker> {
        // Each engine descends its preferred objective form, falling back to the other when absent.
        val lsObj = lsObjective ?: linearObjective
        val btObj = linearObjective ?: lsObjective
        val optimizing = btObj != null

        val workers = ArrayList<PortfolioWorker>(arms.size)
        var btIndex = 0
        arms.forEachIndexed { i, arm ->
            when (arm) {
                is ArmRef.Ls -> {
                    val cfg = arm.config
                    val session = LocalSearchSolver(
                        problem,
                        strategy = cfg.strategy,
                        optimizeStrategy = cfg.optimizeStrategy,
                        restartPolicy = cfg.restartPolicy,
                        definitionalSweep = definitionalSweep,
                        perMoveInvariants = definitionalSweep != null && cfg.perMoveInvariants,
                    ).session()
                    val label = "ls/${cfg.label}"
                    val params = LocalSearchParams(
                        randomSeed = seed + i,
                        costShaping = CostShaping.Linear(lambda = lsLambda),
                        onEvent = onEvent?.let { sink -> { e -> sink(label, e) } },
                    )
                    // Expose the LS warm-start seam so SequentialPortfolio can descend a fresh LS
                    // segment from the shared incumbent; the concurrent Portfolio passes none.
                    workers += PortfolioWorker.of(
                        label,
                        session,
                        params,
                        objective = lsObj,
                        withWarmStart = { p, sample -> p.copy(initialAssignment = sample) },
                    )
                }

                is ArmRef.Bt -> {
                    val session = BacktrackSolver(problem).session()
                    val label = "backtrack#$btIndex"
                    btIndex++
                    val workerEvent = onEvent?.let { sink -> { e: SearchEvent -> sink(label, e) } }
                    val params = arm.config.build(seed + 1000L + i, workerEvent)
                    val withBound: ((BacktrackParams, () -> Double) -> BacktrackParams)? =
                        if (optimizing) { p, supplier -> p.copy(objectiveBoundSupplier = supplier) } else null
                    workers += PortfolioWorker.of(label, session, params, objective = btObj, withBound = withBound)
                }
            }
        }
        check(workers.isNotEmpty()) { "portfolio produced no workers" }
        return workers
    }
}
