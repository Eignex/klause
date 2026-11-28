package com.eignex.klause.backtrack

import com.eignex.klause.solver.result.SearchEvent

/**
 * A named complete-search recipe: a label plus a factory that builds its [BacktrackParams] for a worker seed
 * and event sink. The complete-search analogue of [com.eignex.klause.localsearch.strategy.LocalSearchRecipe],
 * the unit a portfolio arm wraps. [build] is a pure factory, so one recipe is safe to reuse across worker slots.
 */
class BacktrackRecipe(
    /** External name (CLI / telemetry). */
    val label: String,
    /** Fresh params for a worker on the given seed, wired to emit [SearchEvent]s through the sink. */
    val build: (seed: Long, onEvent: ((SearchEvent) -> Unit)?) -> BacktrackParams,
)
