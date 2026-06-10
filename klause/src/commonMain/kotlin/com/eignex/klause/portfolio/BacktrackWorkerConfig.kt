package com.eignex.klause.portfolio

import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.RegressionVariableHeuristic
import com.eignex.klause.solver.backtrack.SolutionGuided

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
 *  - **COP**: `satOptimized · conflictDriven · linucb · free` — SAT-optimized first (the #117
 *    pigeonhole/dense-3SAT guard stays at slot 0 for any pool with ≥1 backtrack worker), then the
 *    conflict-driven workhorse, then the learned LinUCB routing/feasibility-reach arm, then the bare
 *    free engine for plateau diversity. Each prunes on the shared objective bound.
 *  - **CSP**: `satOptimized · conflictDriven · free` — **linucb dropped**: it is the COP routing
 *    arm and its per-decision contextual scoring buys nothing on pure satisfaction (objective-
 *    independent features, no bound to exploit), so a CSP would only pay the overhead.
 */
internal data class BacktrackWorkerConfig(
    val label: String,
    /** Fresh params for a worker on [seed], wired to emit [SearchEvent]s through [onEvent]. */
    val build: (seed: Long, onEvent: ((SearchEvent) -> Unit)?) -> BacktrackParams,
) {
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

        private val copOrder = listOf(satOptimized(), conflictDriven(), linUcb(), free())
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
