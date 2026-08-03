package com.eignex.klause.portfolio

import com.eignex.klause.factor.objective.objectiveBoundOverlay
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSession
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.FeasibleDescent
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.solver.BakedProblem
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent

/**
 * A portfolio arm wrapping a curated [LocalSearchRecipe] for execution: it materialises the recipe into a
 * runnable [LocalSearchSession] worker. The recipe owns the four axes (restart included, in its
 * schedule); this adapter owns only the run-time wiring (λ-shaping, warm-start, event sink).
 */
internal class LocalSearchWorkerConfig(val recipe: LocalSearchRecipe) : WorkerConfig {

    override val label: String get() = recipe.label

    /** Build an LS worker: its [LocalSearchSolver] session (with the per-move invariant network when
     *  a [definitionalSweep] is present and the recipe enables it) + λ-shaped params, exposing the
     *  warm-start seam so a [SequentialPortfolio] can resume a segment from the shared incumbent. The
     *  restart cadence rides on the recipe's `strategy.schedule.restart`. Label is `ls/<label>`. */
    override fun materialize(
        problem: BakedProblem,
        index: Int,
        armId: Int,
        seed: Long,
        lsLambda: Double,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        pools: SharedPools?, // solutions only: local search neither learns nor consumes clauses or cuts
    ): PortfolioWorker {
        // On a COP, every arm optimizes. A recipe that drives objective descent itself (CBLS, SA) needs
        // nothing; a violation-native one (probSAT / WalkSAT / feasibility-jump) gets an `objective ≤
        // incumbent` ratchet overlaid on its problem and the shared bound to tighten — so no arm bails at
        // feasibility on a COP. A CSP (no objective) leaves every arm a pure feasibility finder.
        val (effectiveProblem, boundHandle) = if (objective != null &&
            recipe.feasibleDescent == FeasibleDescent.RatchetAsConstraint
        ) {
            objectiveBoundOverlay(problem, objective) ?: (problem to null)
        } else {
            problem to null
        }
        val session = LocalSearchSolver(
            effectiveProblem.bake(),
            strategy = recipe.strategy,
            optimizeStrategy = recipe.optimizeStrategy,
            definitionalSweep = definitionalSweep,
            perMoveInvariants = definitionalSweep != null && recipe.perMoveInvariants,
            seedImplicitOnRestart = recipe.seedImplicitOnRestart,
        ).apply { objectiveBound = boundHandle }.session()
        val workerLabel = "ls/$label"
        var params = LocalSearchParams(
            randomSeed = seed + index,
            costShaping = CostShaping.Linear(lambda = lsLambda),
            // The per-move gradient view of the objective, when the model provides one.
            lsObjective = lsObjective,
            onEvent = onEvent?.let { sink -> { e -> sink(workerLabel, e) } },
            // Keep a single over-populated constraint kind from steering the initial descent; a
            // no-op for the pool's weight-blind arms.
            normalizeWeightsByClass = true,
        )
        // Bidirectional cross-engine flow (#644): publish incumbents this arm finds and, on restart, anchor
        // on the pool's global best — so LS and backtrack incumbents circulate both ways through the pool.
        pools?.solutions?.let { sols ->
            params = params.copy(improvedSolutionSink = sols::publish, pooledSolutionSupplier = sols::best)
        }
        return PortfolioWorker.of(
            workerLabel,
            armId,
            session,
            params,
            objective = objective,
            withWarmStart = { p, sample -> p.copy(initialAssignment = sample) },
        )
    }

    companion object {
        /** A fresh instance of the pool config named [label] (the string boundary). */
        fun byLabel(label: String): LocalSearchWorkerConfig = LocalSearchWorkerConfig(LocalSearchCatalog.byLabel(label))

        /** The credit-ordered LS pool for [kind] — [LocalSearchCatalog.ranked] wrapped as arms. */
        fun ranked(kind: Kind): List<LocalSearchWorkerConfig> =
            LocalSearchCatalog.ranked(kind).map { LocalSearchWorkerConfig(it) }

        /** The top-[count] prefix of [kind]'s credit-ordered pool (wrapping past the pool size) —
         *  `-p <n>` maps straight onto this. Every slot is a fresh instance even when arms repeat. */
        fun diverse(kind: Kind, count: Int): List<LocalSearchWorkerConfig> =
            LocalSearchCatalog.diverse(kind, count).map { LocalSearchWorkerConfig(it) }
    }
}
