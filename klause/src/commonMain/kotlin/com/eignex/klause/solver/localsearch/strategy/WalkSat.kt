package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * WalkSAT extended to mixed Boolean/integer moves. Pick a violated factor uniformly, ask it
 * for repair-move suggestions, then either flip a random suggestion (probability [noise]) or
 * pick the suggestion with the smallest break count (ties broken uniformly at random).
 *
 * Short-term tabu filtering, aspiration, and dynamic tenure are delegated to [tabu]; see
 * [TabuFilter] for the available knobs. Default: tenure 10 with the
 * "drop the filter when every candidate is tabu" aspiration.
 */
open class WalkSat(
    val noise: Double = 0.5,
    val tabu: TabuFilter = TabuFilter(tenure = 10),
) : Strategy {

    /** Subclass hook for parameter scheduling. [AdaptiveWalkSat] overrides to plug in a
     *  [NoiseController]; the default returns the constructor-time [noise] unchanged. */
    protected open fun currentNoise(state: LocalSearchState): Double = noise

    override fun pickMove(state: LocalSearchState): Move? {
        val raw = state.proposeMovesFromRandomViolated() ?: return null
        val moves = tabu.filter(state, raw)
        if (state.rng.nextDouble() < currentNoise(state)) {
            return moves[state.rng.nextInt(moves.size)]
        }
        // Greedy pick on the shaped break score; under no shaping this is identical to
        // picking on the raw integer break score.
        return state.greedyPickByShapedBreak(moves)
    }
}
