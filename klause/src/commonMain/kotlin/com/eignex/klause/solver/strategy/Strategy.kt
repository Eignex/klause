package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.SolverState

/** Picks the next move to commit. Returns `null` to signal the solver should restart. */
interface Strategy {
    fun pickMove(state: SolverState): Move?
}
