package com.eignex.klause.backtrack

import com.eignex.klause.solver.result.SearchEvent

/**
 * A named backtrack (complete-search) recipe: a label plus a factory that builds its [BacktrackParams]
 * for a worker seed and event sink. The complete-search analogue of the local-search
 * [com.eignex.klause.localsearch.strategy.LsRecipe] — the public unit a portfolio arm wraps, so a
 * caller can inject a pool of named backtrack arms (`PortfolioScenario.btPool`, the CLI's backtrack
 * arm resolution) exactly the way it injects local-search recipes, rather than passing raw
 * [BacktrackParams] templates. A backtrack recipe holds no per-search mutable state, so [build] is a
 * pure factory and one recipe value is safe to reuse across worker slots.
 */
class BacktrackRecipe(
    /** External name (CLI / campaign / telemetry). */
    val label: String,
    /** Fresh params for a worker on the given seed, wired to emit [SearchEvent]s through the sink. */
    val build: (seed: Long, onEvent: ((SearchEvent) -> Unit)?) -> BacktrackParams,
)
