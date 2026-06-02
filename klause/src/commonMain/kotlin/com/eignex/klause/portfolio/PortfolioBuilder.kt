package com.eignex.klause.portfolio

import com.eignex.klause.solver.Problem
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
 *  - **Pure local search** (e.g. the MiniZinc LS competition): `backtrackWorkers = 0`. No CP
 *    is involved, so nothing seeds the LS — competition-safe by construction.
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
) {
    init {
        require(localSearchWorkers >= 0 && backtrackWorkers >= 0) { "worker counts must be ≥ 0" }
        require(localSearchWorkers + backtrackWorkers >= 1) { "a portfolio needs at least one worker" }
    }
}

/** Materialises a [Portfolio] for [problem] from a [PortfolioSpec]. The single entry point the
 *  CLI and bench call — they differ only in the [PortfolioSpec] they pass. */
object PortfolioBuilder {
    fun build(problem: Problem, spec: PortfolioSpec): Portfolio {
        val workers = ArrayList<PortfolioWorker>(spec.localSearchWorkers + spec.backtrackWorkers)

        // Local-search workers: the diverse CBLS-led palette, one distinct (strategy, restart)
        // per worker, each on its own seed. Linear λ shaping so the optimize phase feels the
        // objective (matches the shipped CLI LS config).
        if (spec.localSearchWorkers > 0) {
            LocalSearchWorkerConfig.diverse(spec.localSearchWorkers).forEachIndexed { i, cfg ->
                val session = LocalSearchSolver(
                    problem,
                    strategy = cfg.strategy,
                    optimizeStrategy = cfg.optimizeStrategy,
                    restartPolicy = cfg.restartPolicy,
                ).session()
                val params = LocalSearchParams(
                    randomSeed = spec.seed + i,
                    costShaping = CostShaping.Linear(lambda = spec.lsLambda),
                )
                workers += PortfolioWorker.of("ls/${cfg.label}", session, params)
            }
        }

        // Backtrack workers: seed diversity, plus a CDCL/VSIDS variant every other worker for
        // satisfaction robustness. Each injects the shared objective bound so a tighter
        // incumbent from any worker prunes the others' subtrees.
        repeat(spec.backtrackWorkers) { i ->
            val session = BacktrackSolver(problem).session()
            val params = if (i % 2 == 0) {
                BacktrackParams(randomSeed = spec.seed + 1000L + i, variableHeuristic = Vsids(), phaseSaving = true, lubyRestartBase = 100L)
            } else {
                BacktrackParams(randomSeed = spec.seed + 1000L + i)
            }
            workers += PortfolioWorker.of("backtrack#$i", session, params) { p, supplier ->
                p.copy(objectiveBoundSupplier = supplier)
            }
        }
        return Portfolio(workers)
    }
}
