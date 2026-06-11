package com.eignex.klause.portfolio

import com.eignex.klause.solver.DefinitionalSweep
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.RegressionVariableHeuristic
import com.eignex.klause.solver.backtrack.SolutionGuided
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
 *    conflict-driven workhorse, then the LP-focused arm (the conflict-driven core with the whole
 *    structurally-applicable LP-relaxation family on, [BacktrackParams.lpAuto]), then the learned
 *    LinUCB routing/feasibility-reach arm, then the bare free engine for plateau diversity. Each
 *    prunes on the shared objective bound.
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
     *  on the shared incumbent when an objective is present, and a per-arm [PoolClauseExchange] when
     *  [clausePool] is supplied (cross-arm learned-clause sharing — the lp arm's globally valid
     *  Farkas nogoods travel through it like any other glue clause). LS-only knobs ([lsLambda],
     *  [lsObjective], [definitionalSweep]) are ignored. The label is `backtrack#<index>`. */
    override fun materialize(
        problem: Problem,
        index: Int,
        seed: Long,
        lsLambda: Double,
        objective: LinearObjective?,
        lsObjective: IncrementalObjective?,
        definitionalSweep: DefinitionalSweep?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)?,
        clausePool: SharedClausePool?,
    ): PortfolioWorker {
        val workerLabel = "backtrack#$index"
        val workerEvent = onEvent?.let { sink -> { e: SearchEvent -> sink(workerLabel, e) } }
        var params = build(seed + 1000L + index, workerEvent)
        if (clausePool != null) params = params.copy(clauseExchange = PoolClauseExchange(clausePool))
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
        fun satOptimized() = BacktrackWorkerConfig("satOptimized") { seed, onEvent ->
            BacktrackPresets.satOptimized(randomSeed = seed, onEvent = onEvent)
        }

        /** LastConflict + VSIDS + solution-guided values — the general-COP bound workhorse. */
        fun conflictDriven() = BacktrackWorkerConfig("conflictDriven") { seed, onEvent ->
            BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent)
        }

        /** The learned LinUCB variable heuristic ([RegressionVariableHeuristic], #8) on
         *  solution-guided values — the COP routing / feasibility-reach diversity arm. */
        fun linUcb() = BacktrackWorkerConfig("linucb") { seed, onEvent ->
            BacktrackParams(
                randomSeed = seed,
                variableHeuristic = RegressionVariableHeuristic.linUcb(seed = seed),
                valueHeuristic = SolutionGuided(IndomainMin),
                phaseSaving = true,
                lubyRestartBase = 256L,
                onEvent = onEvent,
            )
        }

        /** The bare free engine (default heuristics, Luby restarts) — plateau diversity. */
        fun free() = BacktrackWorkerConfig("free") { seed, onEvent ->
            BacktrackParams(randomSeed = seed, lubyRestartBase = 256L, onEvent = onEvent)
        }

        /** The LP-focused arm: the conflict-driven core with the whole structurally-applicable
         *  LP-relaxation family enabled via [BacktrackParams.lpAuto] (relaxation bounding, cuts +
         *  pool, Farkas learning, objective propagation, fixpoint, rounding probe — see
         *  `LpAutoConfig`). A no-op fallback to plain conflict-driven on models with no
         *  LP-applicable structure. COP-only: the LP machinery lives on the minimisation path. */
        fun lpFocused() = BacktrackWorkerConfig("lp") { seed, onEvent ->
            BacktrackPresets.conflictDriven(randomSeed = seed, onEvent = onEvent).copy(lpAuto = true)
        }

        private val copOrder = listOf(satOptimized(), conflictDriven(), lpFocused(), linUcb(), free())
        private val cspOrder = listOf(satOptimized(), conflictDriven(), free())

        /** The credit-ordered backtrack pool for [kind] (see the class KDoc). */
        fun ranked(kind: Kind): List<BacktrackWorkerConfig> = when (kind) {
            Kind.COP -> copOrder
            Kind.CSP -> cspOrder
        }

        /** The top-[count] prefix of [ranked], wrapping past the pool size so larger pools repeat
         *  the strong arms on fresh seeds (seed-twin diversity for luck-bound close calls). */
        fun diverse(kind: Kind, count: Int): List<BacktrackWorkerConfig> {
            require(count >= 1) { "count must be ≥ 1" }
            val order = ranked(kind)
            return List(count) { order[it % order.size] }
        }
    }
}
