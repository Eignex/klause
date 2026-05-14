package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.localsearch.strategy.Strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/** Picks the next move to commit. Returns `null` to signal the solver should restart. */
interface Strategy {
    fun pickMove(state: LocalSearchState): Move?
}
