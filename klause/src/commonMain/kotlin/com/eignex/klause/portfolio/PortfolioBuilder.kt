package com.eignex.klause.portfolio

import com.eignex.klause.solver.DefinitionalSweep
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.Vsids
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver

/**
 * Declarative recipe for a [Portfolio] over a single [Problem]. Both engine counts are knobs
 * the CLI / bench set, so one builder serves every use:
 *
 *  - **Pure local search**: `backtrackWorkers = 0`. No CP is involved, so nothing seeds the LS —
 *    a self-contained local-search pool with no CP dependency.
 *  - **Complete / proof**: `localSearchWorkers = 0`.
 *  - **Hybrid**: both > 0 — LS streams good incumbents fast while backtrack tightens the bound
 *    and can prove optimality, sharing the incumbent through the portfolio.
 *
 * [seed] is the base RNG seed; each worker offsets it so the pool explores distinct trajectories.
 */
data class PortfolioSpec(
    /** Number of diverse local-search workers (CBLS-led palette; see [LocalSearchWorkerConfig.diverse]). */
    val localSearchWorkers: Int = 0,
    /** Number of backtrack (complete) workers, with seed + light heuristic diversity. */
    val backtrackWorkers: Int = 0,
    /** Base RNG seed; worker `i` uses an offset of it. */
    val seed: Long = 0L,
    /** Objective-shaping λ for the LS workers' optimize phase (mirrors the CLI's CBLS λ=1.0). */
    val lsLambda: Double = 1.0,
    /**
     * Explicit LS worker-config selection by pool label (see `LocalSearchWorkerConfig.poolLabels`;
     * the magic value `["all"]` selects the entire pool). When non-null this overrides
     * [localSearchWorkers]/the curated palette — the campaign knob for measuring arbitrary
     * config mixes with per-worker attribution.
     */
    val lsConfigLabels: List<String>? = null,
) {
    init {
        require(localSearchWorkers >= 0 && backtrackWorkers >= 0) { "worker counts must be ≥ 0" }
        require(localSearchWorkers + backtrackWorkers >= 1 || !lsConfigLabels.isNullOrEmpty()) {
            "a portfolio needs at least one worker"
        }
    }
}

/** Materialises a [Portfolio] for [problem] from a [PortfolioSpec]. The single entry point the
 *  CLI and bench call — they differ only in the [PortfolioSpec] they pass. */
object PortfolioBuilder {
    /**
     * Build the portfolio.
     *
     * For optimisation the two objective representations differ per engine but agree on the
     * scalar bound (#63): [lsObjective] is the functional/gradient objective the local-search
     * workers descend (the per-move gradient that keeps CBLS optimising), [linearObjective] is
     * the [com.eignex.klause.solver.LinearObjective] the backtrack workers bound-prune on. A
     * mixed pool therefore no longer collapses both engines onto one representation — each worker
     * gets its preferred form, and the shared incumbent bound stays comparable because every
     * worker minimises the same objective var.
     *
     * Either objective may be null: pass both null for a satisfaction-only portfolio ([solve]),
     * or just one if the model only provides that form (each engine falls back to the other when
     * its preferred representation is absent).
     *
     * [onEvent] threads the [SearchEvent] seam through to every worker, tagged with the worker's
     * label (`ls/<config>` or `backtrack#<i>`). Workers run concurrently, so the listener is
     * invoked from multiple threads — it must be thread-safe and cheap. `null` (default) leaves
     * every worker unobserved.
     */
    fun build(
        problem: Problem,
        spec: PortfolioSpec,
        lsObjective: Objective? = null,
        linearObjective: Objective? = null,
        /** Definitional sweep threaded into every LS worker (see
         *  [com.eignex.klause.solver.DefinitionalSweep]); null = unchanged behavior. */
        definitionalSweep: DefinitionalSweep? = null,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)? = null,
    ): Portfolio {
        val workers = ArrayList<PortfolioWorker>(spec.localSearchWorkers + spec.backtrackWorkers)

        // Local-search workers: the diverse CBLS-led palette, one distinct (strategy, restart)
        // per worker, each on its own seed. Linear λ shaping so the optimize phase feels the
        // objective (matches the shipped CLI LS config). Each descends the functional/gradient
        // objective when the model provides one (falling back to the linear form otherwise).
        val lsObj = lsObjective ?: linearObjective
        val lsConfigs = when {
            spec.lsConfigLabels != null && spec.lsConfigLabels == listOf("all") -> LocalSearchWorkerConfig.pool()
            spec.lsConfigLabels != null -> spec.lsConfigLabels.map { LocalSearchWorkerConfig.byLabel(it) }
            spec.localSearchWorkers > 0 -> LocalSearchWorkerConfig.diverse(spec.localSearchWorkers)
            else -> emptyList()
        }
        if (lsConfigs.isNotEmpty()) {
            lsConfigs.forEachIndexed { i, cfg ->
                val session = LocalSearchSolver(
                    problem,
                    strategy = cfg.strategy,
                    optimizeStrategy = cfg.optimizeStrategy,
                    restartPolicy = cfg.restartPolicy,
                    definitionalSweep = definitionalSweep,
                    perMoveInvariants = definitionalSweep != null,
                ).session()
                val label = "ls/${cfg.label}"
                val params = LocalSearchParams(
                    randomSeed = spec.seed + i,
                    costShaping = CostShaping.Linear(lambda = spec.lsLambda),
                    onEvent = onEvent?.let { sink -> { e -> sink(label, e) } },
                )
                workers += PortfolioWorker.of(label, session, params, objective = lsObj)
            }
        }

        // Backtrack workers: seed diversity, plus a CDCL/VSIDS variant every other worker for
        // satisfaction robustness. Each bounds on the linear objective (falling back to the
        // functional form if only that exists) and injects the shared objective bound so a
        // tighter incumbent from any worker prunes the others' subtrees.
        val btObj = linearObjective ?: lsObjective
        repeat(spec.backtrackWorkers) { i ->
            val session = BacktrackSolver(problem).session()
            val label = "backtrack#$i"
            val workerEvent = onEvent?.let { sink -> { e: SearchEvent -> sink(label, e) } }
            val params = if (i % 2 == 0) {
                BacktrackParams(
                    randomSeed = spec.seed + 1000L + i,
                    variableHeuristic = Vsids(),
                    phaseSaving = true,
                    lubyRestartBase = 100L,
                    onEvent = workerEvent,
                )
            } else {
                BacktrackParams(randomSeed = spec.seed + 1000L + i, onEvent = workerEvent)
            }
            workers += PortfolioWorker.of(label, session, params, objective = btObj) { p, supplier ->
                p.copy(objectiveBoundSupplier = supplier)
            }
        }
        return Portfolio(workers)
    }
}
