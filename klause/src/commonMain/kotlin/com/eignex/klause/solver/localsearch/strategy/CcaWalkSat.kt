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
        val raw = state.proposeMovesFromRandomViolated() ?: return null
        val ccEligible = raw.filter { confChanged(state, it) }
        val afterCc = if (ccEligible.isEmpty()) raw else ccEligible
        val moves = tabu.filter(state, afterCc)
        if (state.rng.nextDouble() < noise) {
            return moves[state.rng.nextInt(moves.size)]
        }
        return state.greedyPickByShapedBreak(moves)
    }

    private fun confChanged(state: LocalSearchState, move: Move): Boolean = when (move) {
        is Move.BoolFlip -> state.boolConfChange[move.varId]
        is Move.IntSet -> state.intConfChange[move.varId]
        is Move.SetToggle -> state.setConfChange[move.setVarId]
        // Compound counts as conf-changed iff *all* parts are conf-changed — every
        // affected var must have moved since its last touch for the move to be eligible.
        is Move.Compound -> move.parts.all { confChanged(state, it) }
    }
}
