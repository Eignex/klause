package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * WalkSAT with CCASat-style Configuration Checking. Identical to [WalkSat] except that
 * candidate moves are first filtered to those targeting a variable whose configuration
 * has changed since its last flip — see [LocalSearchState.boolConfChange] /
 * [LocalSearchState.intConfChange]. This breaks short flip-unflip cycles without needing a
 * tabu tenure long enough to be globally disruptive.
 *
 * Aspiration: when every candidate is CC-blocked, the filter is dropped (mirrors the
 * existing all-tabu fallback). Tabu filtering then runs on what's left, with the same
 * aspiration. Selection is greedy on break-score with [noise]-probability random pick.
 */
class CcaWalkSat(
    val noise: Double = 0.5,
    val tabu: TabuFilter = TabuFilter(tenure = 10),
) : Strategy {

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        val factorId = state.violated.random(state.rng)
        val factor = state.factors[factorId]
        state.moveSink.clear()
        factor.proposeRepairMoves(state, factorId, state.moveSink)
        val raw = state.moveSink.list
        if (raw.isEmpty()) return null

        val ccEligible = raw.filter { confChanged(state, it) }
        val afterCc = if (ccEligible.isEmpty()) raw else ccEligible

        val moves = tabu.filter(state, afterCc)

        if (state.rng.nextDouble() < noise) {
            return moves[state.rng.nextInt(moves.size)]
        }
        // Same shaped-break pattern as WalkSat — reduces to raw integer break when no
        // shaping is configured.
        var bestBreak = Double.POSITIVE_INFINITY
        var bestCount = 0
        var pick: Move? = null
        for (m in moves) {
            val brk = state.shapedBreakScore(m)
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

    private fun confChanged(state: LocalSearchState, move: Move): Boolean = when (move) {
        is Move.BoolFlip -> state.boolConfChange[move.varId]
        is Move.IntSet -> state.intConfChange[move.varId]
        // Compound counts as conf-changed iff *all* parts are conf-changed — every
        // affected var must have moved since its last touch for the move to be eligible.
        is Move.Compound -> move.parts.all { confChanged(state, it) }
    }
}
