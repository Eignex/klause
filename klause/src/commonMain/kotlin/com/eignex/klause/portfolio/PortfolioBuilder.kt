package com.eignex.klause.portfolio

import com.eignex.klause.solver.DefinitionalSweep
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchFactor
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

    /** The three named portfolios every consumer (CLI, bench metrics) selects from — the single
     *  vocabulary, so nothing hand-assembles workers. Counts are the shipped defaults; pass
     *  explicit ones to scale a scenario without leaving the canonical path. */
    companion object {
        /** The open competition class: local search streams incumbents fast while the backtrack
         *  pool tightens the bound and can prove optimality, sharing one incumbent. */
        fun mixed(seed: Long = 0L, localSearchWorkers: Int = 4, backtrackWorkers: Int = 2): PortfolioSpec =
            PortfolioSpec(localSearchWorkers = localSearchWorkers, backtrackWorkers = backtrackWorkers, seed = seed)

        /** Pure local-search pool — no complete search, no CP dependency. */
        fun localSearchOnly(seed: Long = 0L, workers: Int = 6): PortfolioSpec =
            PortfolioSpec(localSearchWorkers = workers, seed = seed)

        /** Pure complete backtrack pool — the diverse CDCL/CP trio (SAT-optimized,
         *  conflict-driven, free) cycled across [workers], each on its own seed. */
        fun backtrackOnly(seed: Long = 0L, workers: Int = 6): PortfolioSpec =
            PortfolioSpec(backtrackWorkers = workers, seed = seed)
    }
}

/** Materialises a [Portfolio] for `problem` from a [PortfolioSpec]. The single entry point the
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
     * Either objective may be null: pass both null for a satisfaction-only portfolio (`solve`),
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
        // The LS engine casts every factor to LocalSearchFactor at state construction, so a
        // problem carrying a propagation-only factor (SubsetSumEq / GaussianXor, #250) would
        // throw the moment an LS worker starts. Both are redundant with their LS-capable
        // siblings, so dropping LS for the whole problem is sound — the backtrack pool still
        // covers it (mixed degrades to backtrack-only on these models).
        val lsCapable = problem.factors.all { it is LocalSearchFactor }
        val lsConfigs = when {
            !lsCapable -> emptyList()
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
                    perMoveInvariants = definitionalSweep != null && cfg.perMoveInvariants,
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

        // Backtrack workers: a diverse trio of complete configs, cycled across workers so even a
        // single backtrack worker gets the strong SAT-optimized config. Each bounds on the linear
        // objective (falling back to the functional form if only that exists) and injects the
        // shared objective bound so a tighter incumbent from any worker prunes the others' subtrees.
        // The three legs are the single-config corpus winners: SAT-optimized takes the
        // pigeonhole / dense-random-3SAT class (#117), conflict-driven takes the scheduling /
        // reach tail, and the bare free engine takes the plateau rows. Beyond the third worker
        // the cycle repeats on fresh seeds, which doubles as seed-twin diversity for luck-bound
        // close calls.
        //  - i % 3 == 0: the SAT-optimized CDCL stack ([BacktrackPresets.satOptimized]) —
        //    adaptive restarts, phase + target phasing, three-tier learned DB;
        //  - i % 3 == 1: the conflict-driven composition ([BacktrackPresets.conflictDriven]) —
        //    last-conflict over VSIDS, solution-guided values, phase saving, Luby restarts;
        //  - i % 3 == 2: the bare free engine on Luby restarts, for raw seed diversity.
        val btObj = linearObjective ?: lsObjective
        repeat(spec.backtrackWorkers) { i ->
            val session = BacktrackSolver(problem).session()
            val label = "backtrack#$i"
            val workerEvent = onEvent?.let { sink -> { e: SearchEvent -> sink(label, e) } }
            val seed = spec.seed + 1000L + i
            val params = when (i % 3) {
                0 -> BacktrackPresets.satOptimized(randomSeed = seed, onEvent = workerEvent)
                1 -> BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = workerEvent)
                else -> BacktrackParams(randomSeed = seed, lubyRestartBase = 256L, onEvent = workerEvent)
            }
            workers += PortfolioWorker.of(label, session, params, objective = btObj) { p, supplier ->
                p.copy(objectiveBoundSupplier = supplier)
            }
        }
        check(workers.isNotEmpty()) {
            "portfolio has no workers: all local-search workers were skipped on a problem with a " +
                "non-LS factor (#250) and no backtrack workers were requested — use backtrackOnly " +
                "or request backtrack workers for such models"
        }
        return Portfolio(workers)
    }
}
