package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpTechnique
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
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
internal class BacktrackWorkerConfig(
    val recipe: BacktrackRecipe,
    internal val zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
    private val toleranceCheck: ((Sample) -> Boolean)? = null,
) : WorkerConfig {

    fun withToleranceCheck(check: (Sample) -> Boolean): BacktrackWorkerConfig =
        BacktrackWorkerConfig(recipe, zeroObjectivePricing, check)

    override val label: String get() = recipe.label

    /** Build a backtrack worker: fresh [BacktrackSolver] session + params from the recipe, bound-pruning
     *  on the shared incumbent when an objective is present, and per-arm [PoolClauseExchange] /
     *  [PoolCutExchange] when [pools] supplies them (cross-arm learned-clause sharing — the lp arm's
     *  globally valid Farkas nogoods travel through it like any other glue clause — and global-cut
     *  sharing). LS-only knobs ([lsLambda], [lsObjective], [definitionalSweep]) are ignored. The label
     *  is `bt/<recipe label>`. */
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
        val workerLabel = "bt/${recipe.label}"
        val workerEvent = onEvent?.let { sink -> { e: SearchEvent -> sink(workerLabel, e) } }
        var params = recipe.build(seed + 1000L + index, workerEvent)
        params = params.copy(zeroObjectivePricing = zeroObjectivePricing, toleranceCheck = toleranceCheck)
        // Shared entries name the worker's position as their origin, so replicas of one arm are credited apart.
        val sharing = HashSet<Contribution>()
        pools?.clauses?.let {
            params = params.copy(clauseExchange = PoolClauseExchange(it, origin = index, tally = pools.contributions))
            sharing += Contribution.Clause
        }
        pools?.cuts?.let {
            params = params.copy(cutExchange = PoolCutExchange(it, origin = index, tally = pools.contributions))
            if (params.separatesCuts()) sharing += Contribution.Cut
        }
        // Wire this arm to the shared objective lower-bound manager when optimising: publish
        // the bounds it proves and tighten its objective floor to the cross-arm maximum.
        if (objective != null) {
            pools?.bounds?.let { bounds ->
                params = params.copy(
                    objectiveLowerBoundSink = { v ->
                        pools.contributions.note(Contribution.Floor, index, bounds.publish(v))
                    },
                    objectiveLowerBoundSupplier = bounds::current,
                )
                sharing += Contribution.Floor
            }
            pools?.varBounds?.let { vb ->
                params = params.copy(
                    globalVarBoundSink = { v, lo, hi -> vb.publish(v, lo, hi, origin = index) },
                    globalVarLowerSupplier = vb::lowerOf,
                    globalVarUpperSupplier = vb::upperOf,
                    globalVarImportSink = { v, lower ->
                        val from = if (lower) vb.lowerOriginOf(v) else vb.upperOriginOf(v)
                        if (from != index) pools.contributions.note(Contribution.Bound, from)
                    },
                )
                sharing += Contribution.Bound
            }
            // Publish this arm's incumbents and, in the other direction, dive toward the verified global
            // best during stable phases (solution phasing). Only STABLE windows consult it, so arms explore.
            pools?.solutions?.let { sols ->
                params = params.copy(
                    improvedSolutionSink = { sample, objective -> sols.offer(sample, objective) },
                    pooledIncumbents = sols,
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
        ).also {
            it.sharedPools = pools
            it.sharing = sharing
        }
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

        /** The top-[count] prefix of [BacktrackCatalog.ranked], wrapping past the pool size so larger
         *  pools repeat the strong arms on fresh seeds (seed-twin diversity for luck-bound close calls).
         *  Each arm is capped under [lpCeiling] (default `AGGRESSIVE`, no overrides = uncapped) and
         *  spends [nodeBudget]. Only arms the model behind [facts] offers the needs of are built. */
        fun diverse(
            kind: Kind,
            count: Int,
            lpCeiling: LpConfig = LpConfig.AGGRESSIVE,
            nodeBudget: NodeBudget? = null,
            zeroObjectivePricing: LpZeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
            facts: ProblemFacts = ProblemFacts.assumed(kind),
            edit: ((BacktrackParams) -> BacktrackParams)? = null,
        ): List<BacktrackWorkerConfig> {
            require(count >= 1) { "count must be ≥ 1" }
            val order = BacktrackCatalog.ranked(kind, facts)
            return List(count) {
                BacktrackWorkerConfig(
                    order[it % order.size].editing(edit).capLp(lpCeiling).spending(nodeBudget),
                    zeroObjectivePricing,
                )
            }
        }
    }
}

// Whether this arm runs a cut separator, the only source of cuts it publishes: the plan's own cut or circuit
// rounds, or a config whose emphasis and overrides admit them for the relaxation to switch on.
private fun BacktrackParams.separatesCuts(): Boolean {
    val config = lpConfig
    return lpPlan.cuts || lpPlan.circuit ||
        (config != null && (config.resolved(LpTechnique.CUTS) || config.resolved(LpTechnique.CIRCUIT)))
}

/** This recipe with [edit] applied to the [BacktrackParams] it builds, per worker so selector state stays
 *  unshared; itself when [edit] is null. Keeps the arm's label. */
internal fun BacktrackRecipe.editing(edit: ((BacktrackParams) -> BacktrackParams)?): BacktrackRecipe =
    if (edit == null) this else BacktrackRecipe(label) { seed, onEvent -> edit(build(seed, onEvent)) }

/** Cap this recipe under [ceiling] (the `--lp` ceiling): each LP arm's config is `cappedUnder` it —
 *  emphasis lowered and the ceiling's per-technique overrides applied — so no arm runs LP above what the
 *  user permitted, and `--lp aggressive,-cuts` / `off,+energetic` toggle individual techniques across the
 *  pool. Non-LP arms keep no LP; an all-`AGGRESSIVE`, no-override ceiling is a no-op. */
internal fun BacktrackRecipe.capLp(ceiling: LpConfig): BacktrackRecipe = BacktrackRecipe(label) { seed, onEvent ->
    val p = build(seed, onEvent)
    val intended = p.lpConfig
    if (intended == null) p else p.copy(lpConfig = intended.cappedUnder(ceiling))
}

/** This recipe with every arm it builds spending [budget], the solve-spanning node allowance. A null
 *  budget leaves the recipe untouched, so an uncapped run composes exactly the pool it would anyway. */
internal fun BacktrackRecipe.spending(budget: NodeBudget?): BacktrackRecipe {
    if (budget == null) return this
    return BacktrackRecipe(label) { seed, onEvent -> build(seed, onEvent).copy(nodeBudget = budget) }
}
