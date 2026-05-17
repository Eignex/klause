package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * WalkSAT extended to mixed Boolean/integer moves. Pick a violated factor uniformly, ask it
 * for repair-move suggestions, then either flip a random suggestion (probability [noise]) or
 * pick the suggestion with the smallest break count (ties broken uniformly at random).
 *
 * Short-term tabu filtering, aspiration, and dynamic tenure are delegated to [tabu]; see
 * [TabuFilter] for the available knobs. Default: tenure 10 with the historical
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
        if (state.violated.isEmpty()) return null
        val factorId = state.violated.random(state.rng)
        val factor = state.factors[factorId]
        state.moveSink.clear()
        factor.proposeRepairMoves(state, factorId, state.moveSink)
        val raw = state.moveSink.list
        if (raw.isEmpty()) return null
        val moves = tabu.filter(state, raw)

        if (state.rng.nextDouble() < currentNoise(state)) {
            return moves[state.rng.nextInt(moves.size)]
        }

        var bestBreak = Int.MAX_VALUE
        var bestCount = 0
        var pick: Move? = null
        for (m in moves) {
            val brk = state.breakScore(m)
            if (brk < bestBreak) {
                bestBreak = brk
                bestCount = 1
                pick = m
            } else if (brk == bestBreak) {
                bestCount++
                if (state.rng.nextInt(bestCount) == 0) pick = m
            }
        }
        return pick
    }
}
