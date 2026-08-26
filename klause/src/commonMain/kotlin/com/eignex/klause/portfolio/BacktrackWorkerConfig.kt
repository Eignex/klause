package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent

/**
 * A portfolio arm wrapping a [BacktrackRecipe] for execution — the backtrack counterpart of
 * [LocalSearchWorkerConfig], so every portfolio arm, LS or backtrack, is declared in one place and
 * selected by the same [PortfolioComposition] decision algorithm. The recipe (from [BacktrackCatalog],
 * the public boundary a caller injects via `PortfolioScenario.btPool`) owns the config; this adapter
 * owns only the run-time wiring (shared pools, bound pruning, event sink) and the portfolio-side
 * `--lp` ceiling capping.
 */
internal class BacktrackWorkerConfig(val recipe: BacktrackRecipe) : WorkerConfig {

    override val label: String get() = recipe.label

    /** Build a backtrack worker: fresh [BacktrackSolver] session + params from the recipe, bound-pruning
     *  on the shared incumbent when an objective is present, and per-arm [PoolClauseExchange] /
     *  [PoolCutExchange] when [pools] supplies them (cross-arm learned-clause sharing — the lp arm's
     *  globally valid Farkas nogoods travel through it like any other glue clause — and global-cut
     *  sharing). LS-only knobs ([lsLambda], [lsObjective], [definitionalSweep]) are ignored. The label
     *  is `backtrack#<index>`. */
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
        pools: SharedPools?,
    ): PortfolioWorker {
        val workerLabel = "backtrack#$index"
        val workerEvent = onEvent?.let { sink -> { e: SearchEvent -> sink(workerLabel, e) } }
        var params = recipe.build(seed + 1000L + index, workerEvent)
        pools?.clauses?.let { params = params.copy(clauseExchange = PoolClauseExchange(it)) }
        pools?.cuts?.let { params = params.copy(cutExchange = PoolCutExchange(it)) }
        // Wire this arm to the shared objective lower-bound manager when optimising: publish
        // the bounds it proves and tighten its objective floor to the cross-arm maximum.
        if (objective != null) {
            pools?.bounds?.let { bounds ->
                params = params.copy(
                    objectiveLowerBoundSink = bounds::publish,
                    objectiveLowerBoundSupplier = bounds::current,
                )
            }
            pools?.varBounds?.let { vb ->
                params = params.copy(
                    globalVarBoundSink = vb::publish,
                    globalVarLowerSupplier = vb::lowerOf,
                    globalVarUpperSupplier = vb::upperOf,
                )
            }
            // Publish this arm's incumbents and, in the other direction, dive toward the pool's global best
            // during stable phases (solution phasing). Only STABLE windows consult it, so arms still explore.
            pools?.solutions?.let { sols ->
                params = params.copy(
                    improvedSolutionSink = sols::publish,
                    pooledSolutionSupplier = sols::best,
                    solutionPhasing = true,
                )
            }
        }
        // A pure CSP has no bound to prune on, so withBound is wired only when optimising.
        val withBound: ((BacktrackParams, () -> Double) -> BacktrackParams)? =
            if (objective != null) { p, supplier -> p.copy(objectiveBoundSupplier = supplier) } else null
        return PortfolioWorker.of(
            workerLabel,
            armId,
            BacktrackSolver(problem).session(),
            params,
            objective = objective,
            withBound = withBound,
        )
    }

    companion object {
        /** The credit-ordered backtrack pool for [kind] — [BacktrackCatalog.ranked] wrapped as arms. */
        fun ranked(kind: Kind): List<BacktrackWorkerConfig> =
            BacktrackCatalog.ranked(kind).map { BacktrackWorkerConfig(it) }

        /** A fresh arm for the recipe named [label]. */
        fun byLabel(label: String): BacktrackWorkerConfig = BacktrackWorkerConfig(BacktrackCatalog.byLabel(label))

        /** Wrap a pre-built [BacktrackParams] template as a recipe — the model-derived annotation arm.
         *  The template's seed and event sink are overridden per slot, so one template is safe to reuse. */
        fun ofParams(label: String, template: BacktrackParams): BacktrackRecipe =
            BacktrackRecipe(label) { seed, onEvent -> template.copy(randomSeed = seed, onEvent = onEvent) }

        /** Cap this recipe under [ceiling] (the `--lp` ceiling): each LP arm's config is `cappedUnder` it —
         *  emphasis lowered and the ceiling's per-technique overrides applied — so no arm runs LP above
         *  what the user permitted, and `--lp aggressive,-cuts` / `off,+energetic` toggle individual
         *  techniques across the pool. Non-LP arms keep no LP; an all-`AGGRESSIVE`, no-override ceiling
         *  is a no-op. */
        private fun BacktrackRecipe.capLp(ceiling: LpConfig): BacktrackRecipe =
            BacktrackRecipe(label) { seed, onEvent ->
                val p = build(seed, onEvent)
                val intended = p.lpConfig
                if (intended == null) p else p.copy(lpConfig = intended.cappedUnder(ceiling))
            }

        /** The top-[count] prefix of [BacktrackCatalog.ranked], wrapping past the pool size so larger
         *  pools repeat the strong arms on fresh seeds (seed-twin diversity for luck-bound close calls).
         *  Each arm is capped under [lpCeiling] (default `AGGRESSIVE`, no overrides = uncapped). */
        fun diverse(kind: Kind, count: Int, lpCeiling: LpConfig = LpConfig.AGGRESSIVE): List<BacktrackWorkerConfig> {
            require(count >= 1) { "count must be ≥ 1" }
            val order = BacktrackCatalog.ranked(kind)
            return List(count) { BacktrackWorkerConfig(order[it % order.size].capLp(lpCeiling)) }
        }
    }
}
