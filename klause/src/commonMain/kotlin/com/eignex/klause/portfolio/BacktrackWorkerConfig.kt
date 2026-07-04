package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.SelectorPortfolio
import com.eignex.klause.backtrack.lp.LpConfig
import com.eignex.klause.backtrack.lp.LpEmphasis
import com.eignex.klause.backtrack.selector.ActivityBasedSearch
import com.eignex.klause.backtrack.selector.ConflictOrdering
import com.eignex.klause.backtrack.selector.DomWdeg
import com.eignex.klause.backtrack.selector.Impact
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.RegressionVariableSelector
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent

/**
 * A portfolio arm wrapping a [BacktrackRecipe] for execution — the backtrack counterpart of
 * [LocalSearchWorkerConfig], so every portfolio arm, LS or backtrack, is declared in one place and
 * selected by the same [PortfolioComposition] decision algorithm. The recipe is the public boundary
 * (the unit a caller injects via `PortfolioScenario.btPool`); this adapter owns only the run-time
 * wiring (shared pools, bound pruning, event sink).
 *
 * **Per-kind ranking** ([ranked]) is the backtrack half of the #9 tuning surface:
 *  - **COP**: SAT-optimized first (the #117 pigeonhole/dense-3SAT guard stays at slot 0 for any pool
 *    with ≥1 backtrack worker), then the conflict-driven workhorse, the LP-intensity spread, the
 *    learned LinUCB routing arm, the bare free engine, and — kept last, pending a credit pass — the
 *    restart-level selector portfolio and the dom-wdeg / first-fail / activity heuristic arms.
 *  - **CSP**: `linucb` and the LP arms drop out — LP lives on the minimisation path and LinUCB's
 *    contextual scoring buys nothing without an objective to exploit.
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
        problem: Problem,
        index: Int,
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
        }
        // A pure CSP has no bound to prune on, so withBound is wired only when optimising.
        val withBound: ((BacktrackParams, () -> Double) -> BacktrackParams)? =
            if (objective != null) { p, supplier -> p.copy(objectiveBoundSupplier = supplier) } else null
        return PortfolioWorker.of(
            workerLabel,
            BacktrackSolver(problem).session(),
            params,
            objective = objective,
            withBound = withBound,
        )
    }

    companion object {
        /** The strong CDCL/SAT stack (adaptive restarts, target phasing, 3-tier learned DB,
         *  vivification); the #117 guard. Kept at rank 0 for both kinds. */
        private fun satOptimized() = BacktrackRecipe("satOptimized") { seed, onEvent ->
            BacktrackPresets.satOptimized(randomSeed = seed, onEvent = onEvent)
        }

        /** LastConflict + VSIDS + solution-guided values — the general-COP bound workhorse. */
        private fun conflictDriven() = BacktrackRecipe("conflictDriven") { seed, onEvent ->
            BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent)
        }

        /** The learned LinUCB variable heuristic ([RegressionVariableSelector], #8) on
         *  solution-guided values — the COP routing / feasibility-reach diversity arm. */
        private fun linUcb() = BacktrackRecipe("linucb") { seed, onEvent ->
            BacktrackParams(
                randomSeed = seed,
                variableSelector = RegressionVariableSelector.linUcb(seed = seed),
                valueSelector = SolutionGuided(IndomainMin),
                phaseSaving = true,
                lubyRestartBase = 256L,
                onEvent = onEvent,
            )
        }

        /** The bare free engine (default heuristics, Luby restarts) — plateau diversity. */
        private fun free() = BacktrackRecipe("free") { seed, onEvent ->
            BacktrackParams(randomSeed = seed, lubyRestartBase = 256L, onEvent = onEvent)
        }

        /** Classic first-fail: smallest-domain variable + min value — the FD default many models are
         *  written for. A fixed-order diversity arm distinct from the VSIDS/dom-wdeg workhorses. */
        private fun firstFail() = BacktrackRecipe("first-fail") { seed, onEvent ->
            BacktrackParams(
                randomSeed = seed,
                variableSelector = SmallestDomain,
                valueSelector = IndomainMin,
                lubyRestartBase = 256L,
                onEvent = onEvent,
            )
        }

        /** Weighted-degree branching (dom/wdeg) on solution-guided values — a conflict-history variable
         *  order distinct from VSIDS, strong on structured CSP/COP. */
        private fun domWdeg() = BacktrackRecipe("domwdeg") { seed, onEvent ->
            BacktrackParams(
                randomSeed = seed,
                variableSelector = DomWdeg(),
                valueSelector = SolutionGuided(IndomainMin),
                phaseSaving = true,
                lubyRestartBase = 256L,
                onEvent = onEvent,
            )
        }

        /** Activity-based search (ABS) variable order + impact-based value order — a probing-driven
         *  heuristic that reads the propagation reaction, distinct from the conflict-driven arms. */
        private fun activity() = BacktrackRecipe("activity") { seed, onEvent ->
            BacktrackParams(
                randomSeed = seed,
                variableSelector = ActivityBasedSearch(),
                valueSelector = Impact(),
                lubyRestartBase = 256L,
                onEvent = onEvent,
            )
        }

        /** An LP arm: the conflict-driven core with the LP-relaxation family resolved at [emphasis]
         *  (#429). `AGGRESSIVE` is the whole structurally-applicable family (cuts + hulls + probe);
         *  `DEFAULT` is simplex bounding + objective propagation without the expensive cut machinery;
         *  `CONSERVATIVE` is the cheap combinatorial bounds only. A no-op on models with no
         *  LP-applicable structure. COP-only: the LP machinery lives on the minimisation path. */
        private fun lpArm(emphasis: LpEmphasis) = BacktrackRecipe("lp-${emphasis.name.lowercase()}") { seed, onEvent ->
            BacktrackPresets.conflictDriven(
                randomSeed = seed,
                onEvent = onEvent,
            ).copy(lpConfig = LpConfig(emphasis))
        }

        /** A best-bound-dive LP arm: the DEFAULT LP stack plus the `lb_tree_search` primal
         *  subsolver, which explores the branch-and-bound tree best-first before search to land good
         *  incumbents fast. The flag rides the base plan, which the LP auto-config preserves; it is a no-op
         *  when the LP relaxation is off (so a `--lp off` ceiling neutralises it). The other LP primal
         *  heuristic — the rounding probe and its feasibility-pump fallback — is already auto-on for
         *  every LP arm, so no separate pump arm is needed. */
        private fun lpTreeSearchArm() = BacktrackRecipe("lp-lbtree") { seed, onEvent ->
            val base = BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent)
            base.copy(lpConfig = LpConfig(LpEmphasis.DEFAULT), lpPlan = base.lpPlan.copy(lbTreeSearch = true))
        }

        /** A restart-level selector portfolio (#765): one arm that switches its (variable, value)
         *  heuristic at every Luby restart under a UCB1 bandit — VSIDS / dom-wdeg / activity /
         *  conflict-ordering paired with min / impact / solution-guided values — so the arm learns
         *  per instance which complete-search heuristic suits it. A fresh portfolio per worker (it
         *  holds mutable bandit state); both selector slots reference the one instance to share it. */
        private fun selectorSwitch() = BacktrackRecipe("selector-switch") { seed, onEvent ->
            val palette = SelectorPortfolio.ucb1(
                listOf(
                    SelectorPortfolio.Arm("vsids+min", Vsids(), IndomainMin),
                    SelectorPortfolio.Arm("domwdeg+solguided", DomWdeg(), SolutionGuided(IndomainMin)),
                    SelectorPortfolio.Arm("activity+impact", ActivityBasedSearch(), Impact()),
                    SelectorPortfolio.Arm("cos-domwdeg+min", ConflictOrdering(DomWdeg()), IndomainMin),
                ),
            )
            BacktrackParams(
                randomSeed = seed,
                variableSelector = palette.variableSelector,
                valueSelector = palette.valueSelector,
                lubyRestartBase = 100L,
                onEvent = onEvent,
            )
        }

        /** Build the recipe for [arm]'s typed identity — the single place each arm's params originate.
         *  Keeps [BacktrackArm.label] and the produced recipe's [BacktrackRecipe.label] in lockstep. */
        private fun make(arm: BacktrackArm): BacktrackRecipe = when (arm) {
            BacktrackArm.SatOptimized -> satOptimized()
            BacktrackArm.ConflictDriven -> conflictDriven()
            BacktrackArm.LpAggressive -> lpArm(LpEmphasis.AGGRESSIVE)
            BacktrackArm.LpDefault -> lpArm(LpEmphasis.DEFAULT)
            BacktrackArm.LpLbTree -> lpTreeSearchArm()
            BacktrackArm.LpConservative -> lpArm(LpEmphasis.CONSERVATIVE)
            BacktrackArm.LinUcb -> linUcb()
            BacktrackArm.Free -> free()
            BacktrackArm.SelectorSwitch -> selectorSwitch()
            BacktrackArm.FirstFail -> firstFail()
            BacktrackArm.DomWdeg -> domWdeg()
            BacktrackArm.Activity -> activity()
        }

        // COP spread: the OFF arms (satOptimized / conflictDriven / linucb / free) hedge
        // the per-instance LP trade against an LP-intensity spread — AGGRESSIVE (closes the bound hard),
        // DEFAULT (simplex bounding), CONSERVATIVE (cheap combinatorial bounds) — plus the best-bound-dive
        // primal arm. A supplied `--lp` ceiling caps every LP arm via [diverse].
        private val copOrder = listOf(
            BacktrackArm.SatOptimized,
            BacktrackArm.ConflictDriven,
            BacktrackArm.LpAggressive,
            BacktrackArm.LpDefault,
            BacktrackArm.LpLbTree,
            BacktrackArm.LpConservative,
            BacktrackArm.LinUcb,
            BacktrackArm.Free,
            // Restart-level selector portfolio (#765) + latent-axis heuristic arms: kept last pending
            // their cross-seed credit pass, so the tuned diverse(N) prefix is unchanged.
            BacktrackArm.SelectorSwitch,
            BacktrackArm.DomWdeg,
            BacktrackArm.FirstFail,
            BacktrackArm.Activity,
        )
        private val cspOrder = listOf(
            BacktrackArm.SatOptimized,
            BacktrackArm.ConflictDriven,
            BacktrackArm.Free,
            BacktrackArm.SelectorSwitch,
            BacktrackArm.DomWdeg,
            BacktrackArm.FirstFail,
            BacktrackArm.Activity,
        )

        private fun rankedArms(kind: Kind): List<BacktrackArm> = when (kind) {
            Kind.COP -> copOrder
            Kind.CSP -> cspOrder
        }

        /** The credit-ordered backtrack pool for [kind] (see the class KDoc). */
        fun ranked(kind: Kind): List<BacktrackWorkerConfig> = rankedArms(kind).map { BacktrackWorkerConfig(make(it)) }

        private fun fromLabel(label: String): BacktrackArm = BacktrackArm.entries.firstOrNull { it.label == label }
            ?: error("unknown backtrack arm '$label' (have ${BacktrackArm.entries.joinToString { it.label }})")

        /** A fresh config for the arm named [label] (the single string boundary — CLI `arm=`, campaigns). */
        fun byLabel(label: String): BacktrackWorkerConfig = BacktrackWorkerConfig(make(fromLabel(label)))

        /** A fresh [BacktrackRecipe] for the arm named [label] — the public recipe the CLI and
         *  `PortfolioScenario.btPool` inject, symmetric with the LS catalog's `byLabel`. */
        fun recipeByLabel(label: String): BacktrackRecipe = make(fromLabel(label))

        /** Wrap a pre-built [BacktrackParams] template as a recipe — the model-derived annotation arm.
         *  The template's seed and event sink are overridden per slot, so one template is safe to reuse. */
        fun ofParams(label: String, template: BacktrackParams): BacktrackRecipe =
            BacktrackRecipe(label) { seed, onEvent -> template.copy(randomSeed = seed, onEvent = onEvent) }

        /** Every arm label for [kind], in credit order — for enumerating the pool by name (the CLI
         *  `arm=` selector, a credit sweep). */
        fun labels(kind: Kind): List<String> = rankedArms(kind).map { it.label }

        /** Per-arm recipe factories for [kind], in credit order — each builds a fresh recipe. The public
         *  factory shape a campaign or the CLI feeds to `PortfolioScenario.btPool`. */
        fun factories(kind: Kind): List<() -> BacktrackRecipe> = rankedArms(kind).map { arm -> { make(arm) } }

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

        /** The top-[count] prefix of [ranked], wrapping past the pool size so larger pools repeat
         *  the strong arms on fresh seeds (seed-twin diversity for luck-bound close calls). Each arm is
         *  capped under [lpCeiling] (default `AGGRESSIVE`, no overrides = uncapped). */
        fun diverse(kind: Kind, count: Int, lpCeiling: LpConfig = LpConfig.AGGRESSIVE): List<BacktrackWorkerConfig> {
            require(count >= 1) { "count must be ≥ 1" }
            val order = rankedArms(kind)
            return List(count) { BacktrackWorkerConfig(make(order[it % order.size]).capLp(lpCeiling)) }
        }
    }
}

/**
 * Typed identity of every backtrack catalog arm — the backtrack counterpart of
 * [com.eignex.klause.localsearch.strategy.LsArm]. [BacktrackWorkerConfig.ranked] /
 * [BacktrackWorkerConfig.diverse] order and instantiate these; [label] is the external name (CLI
 * `arm=` / campaign / telemetry) and is kept in lockstep with the built recipe's label.
 */
internal enum class BacktrackArm(val label: String) {
    SatOptimized("satOptimized"),
    ConflictDriven("conflictDriven"),
    LpAggressive("lp-aggressive"),
    LpDefault("lp-default"),
    LpLbTree("lp-lbtree"),
    LpConservative("lp-conservative"),
    LinUcb("linucb"),
    Free("free"),
    SelectorSwitch("selector-switch"),
    DomWdeg("domwdeg"),
    FirstFail("first-fail"),
    Activity("activity"),
}
