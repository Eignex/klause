package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.SolverState

/** Picks the next variable to flip given the current solver state. Returns -1 to skip the step. */
interface Strategy {
    fun pickFlip(state: SolverState): Int
}
