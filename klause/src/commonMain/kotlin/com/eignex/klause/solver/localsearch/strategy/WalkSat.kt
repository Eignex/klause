package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.WalkSat

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.SolverState

/**
 * WalkSAT extended to mixed Boolean/integer moves. Pick a violated factor uniformly, ask it
 * for repair-move suggestions, then either flip a random suggestion (probability [noise]) or
 * pick the suggestion with the smallest break count (ties broken uniformly at random).
 *
 * A short-term tabu list (parameter [tabuTenure], default 10) filters out flips of variables
 * touched within the last [tabuTenure] accepted moves to avoid local cycling. Aspiration: when
 * every candidate is taboo, the filter is dropped and the strategy picks from the full set.
 */
class WalkSat(val noise: Double = 0.5, val tabuTenure: Int = 10) : Strategy {

    override fun pickMove(state: SolverState): Move? {
        if (state.violated.isEmpty()) return null
        val factorId = state.violated.random(state.rng)
        val factor = state.factors[factorId]
        state.moveSink.clear()
        factor.proposeRepairMoves(state, factorId, state.moveSink)
        val raw = state.moveSink.list
        if (raw.isEmpty()) return null
        val moves = if (tabuTenure > 0) {
            val nonTaboo = raw.filter { !state.isTaboo(it, tabuTenure) }
            if (nonTaboo.isEmpty()) raw else nonTaboo
        } else raw

        if (state.rng.nextDouble() < noise) {
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
