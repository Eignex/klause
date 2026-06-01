package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/** Picks the next move to commit. Returns `null` to signal the solver should restart. */
interface Strategy {
    /** Pick the next move to apply, or null when none is available. */
    fun pickMove(state: LocalSearchState): Move?
}
