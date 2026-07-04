package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.lp.LpConfig
import com.eignex.klause.backtrack.lp.LpEmphasis
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.RegressionVariableSelector
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent

/**
 * The named pool of backtrack (complete-search) arms — the backtrack counterpart of
 * [LocalSearchWorkerConfig], so every portfolio arm, LS or backtrack, is declared in one place and
 * selected by the same [PortfolioComposition] decision algorithm.
 *
 * Unlike the LS configs, a backtrack arm holds no per-search mutable state: [build] is a pure
 * factory that produces a fresh [BacktrackParams] per worker (closing over only the worker's seed
 * and event sink), so the same config value is safe to reuse across slots.
 *
 * **Per-kind ranking** ([ranked]) is the backtrack half of the #9 tuning surface:
 *  - **COP**: `satOptimized · conflictDriven · lp · linucb · free` — SAT-optimized first (the #117
 *    pigeonhole/dense-3SAT guard stays at slot 0 for any pool with ≥1 backtrack worker), then the
 *    conflict-driven workhorse, then the two LP-intensity arms (the conflict-driven core with the
 *    LP-relaxation family resolved at [BacktrackParams.lpConfig] — AGGRESSIVE then DEFAULT), then the
 *    learned LinUCB routing/feasibility-reach arm, then the bare free engine for plateau diversity.
 *    Each prunes on the shared objective bound.
 *  - **CSP**: `satOptimized · conflictDriven · free` — **linucb dropped**: it is the COP routing
 *    arm and its per-decision contextual scoring buys nothing on pure satisfaction (objective-
 *    independent features, no bound to exploit), so a CSP would only pay the overhead.
 */
internal data class BacktrackWorkerConfig(
    override val label: String,
    /** Fresh params for a worker on the given seed, wired to emit [SearchEvent]s through the sink. */
    val build: (seed: Long, onEvent: ((SearchEvent) -> Unit)?) -> BacktrackParams,
) : WorkerConfig {

    /** Build a backtrack worker: fresh [BacktrackSolver] session + params from [build], bound-pruning
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
        var params = build(seed + 1000L + index, workerEvent)
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
        private fun satOptimized() = BacktrackWorkerConfig("satOptimized") { seed, onEvent ->
            BacktrackPresets.satOptimized(randomSeed = seed, onEvent = onEvent)
        }

        /** LastConflict + VSIDS + solution-guided values — the general-COP bound workhorse. */
        private fun conflictDriven() = BacktrackWorkerConfig("conflictDriven") { seed, onEvent ->
            BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent)
        }

        /** The learned LinUCB variable heuristic ([RegressionVariableSelector], #8) on
         *  solution-guided values — the COP routing / feasibility-reach diversity arm. */
        private fun linUcb() = BacktrackWorkerConfig("linucb") { seed, onEvent ->
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
        private fun free() = BacktrackWorkerConfig("free") { seed, onEvent ->
            BacktrackParams(randomSeed = seed, lubyRestartBase = 256L, onEvent = onEvent)
        }

        /** An LP arm: the conflict-driven core with the LP-relaxation family resolved at [emphasis]
         *  (#429). `AGGRESSIVE` is the whole structurally-applicable family (cuts + hulls + probe);
         *  `DEFAULT` is simplex bounding + objective propagation without the expensive cut machinery;
         *  `CONSERVATIVE` is the cheap combinatorial bounds only. A no-op on models with no
         *  LP-applicable structure. COP-only: the LP machinery lives on the minimisation path. */
        private fun lpArm(emphasis: LpEmphasis) =
            BacktrackWorkerConfig("lp-${emphasis.name.lowercase()}") { seed, onEvent ->
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
        private fun lpTreeSearchArm() = BacktrackWorkerConfig("lp-lbtree") { seed, onEvent ->
            val base = BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent)
            base.copy(lpConfig = LpConfig(LpEmphasis.DEFAULT), lpPlan = base.lpPlan.copy(lbTreeSearch = true))
        }

        /** Build the config for [arm]'s typed identity — the single place each arm's params originate.
         *  Keeps [BacktrackArm.label] and the produced config's [label] in lockstep. */
        private fun make(arm: BacktrackArm): BacktrackWorkerConfig = when (arm) {
            BacktrackArm.SatOptimized -> satOptimized()
            BacktrackArm.ConflictDriven -> conflictDriven()
            BacktrackArm.LpAggressive -> lpArm(LpEmphasis.AGGRESSIVE)
            BacktrackArm.LpDefault -> lpArm(LpEmphasis.DEFAULT)
            BacktrackArm.LpLbTree -> lpTreeSearchArm()
            BacktrackArm.LpConservative -> lpArm(LpEmphasis.CONSERVATIVE)
            BacktrackArm.LinUcb -> linUcb()
            BacktrackArm.Free -> free()
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
        )
        private val cspOrder = listOf(BacktrackArm.SatOptimized, BacktrackArm.ConflictDriven, BacktrackArm.Free)

        private fun rankedArms(kind: Kind): List<BacktrackArm> = when (kind) {
            Kind.COP -> copOrder
            Kind.CSP -> cspOrder
        }

        /** The credit-ordered backtrack pool for [kind] (see the class KDoc). */
        fun ranked(kind: Kind): List<BacktrackWorkerConfig> = rankedArms(kind).map { make(it) }

        private fun fromLabel(label: String): BacktrackArm = BacktrackArm.entries.firstOrNull { it.label == label }
            ?: error("unknown backtrack arm '$label' (have ${BacktrackArm.entries.joinToString { it.label }})")

        /** A fresh config for the arm named [label] (the single string boundary — CLI `arm=`, campaigns). */
        fun byLabel(label: String): BacktrackWorkerConfig = make(fromLabel(label))

        /** Every arm label for [kind], in credit order — for enumerating the pool by name (the CLI
         *  `arm=` selector, a credit sweep). */
        fun labels(kind: Kind): List<String> = rankedArms(kind).map { it.label }

        /** Per-arm factories for [kind], in credit order — each builds a fresh config. Symmetric with
         *  the LS catalog; backtrack arms hold no per-search state, so a shared value would also be
         *  safe, but a factory keeps the campaign/attribution API uniform across engines. */
        fun factories(kind: Kind): List<() -> BacktrackWorkerConfig> = rankedArms(kind).map { arm -> { make(arm) } }

        /** Cap this arm under [ceiling] (the `--lp` ceiling): each LP arm's config is `cappedUnder` it —
         *  emphasis lowered and the ceiling's per-technique overrides applied — so no arm runs LP above
         *  what the user permitted, and `--lp aggressive,-cuts` / `off,+energetic` toggle individual
         *  techniques across the pool. Non-LP arms keep no LP; an all-`AGGRESSIVE`, no-override ceiling
         *  is a no-op. */
        private fun BacktrackWorkerConfig.capLp(ceiling: LpConfig): BacktrackWorkerConfig =
            copy(build = { seed, onEvent ->
                val p = build(seed, onEvent)
                val intended = p.lpConfig
                if (intended == null) p else p.copy(lpConfig = intended.cappedUnder(ceiling))
            })

        /** The top-[count] prefix of [ranked], wrapping past the pool size so larger pools repeat
         *  the strong arms on fresh seeds (seed-twin diversity for luck-bound close calls). Each arm is
         *  capped under [lpCeiling] (default `AGGRESSIVE`, no overrides = uncapped). */
        fun diverse(kind: Kind, count: Int, lpCeiling: LpConfig = LpConfig.AGGRESSIVE): List<BacktrackWorkerConfig> {
            require(count >= 1) { "count must be ≥ 1" }
            val order = ranked(kind)
            return List(count) { order[it % order.size].capLp(lpCeiling) }
        }
    }
}

/**
 * Typed identity of every backtrack catalog arm — the backtrack counterpart of
 * [com.eignex.klause.localsearch.strategy.LsArm]. [BacktrackWorkerConfig.ranked] /
 * [BacktrackWorkerConfig.diverse] order and instantiate these; [label] is the external name (CLI
 * `arm=` / campaign / telemetry) and is kept in lockstep with the built config's label.
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
}
