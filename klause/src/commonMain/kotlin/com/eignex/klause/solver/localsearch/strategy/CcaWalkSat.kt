package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.CcaWalkSat
import com.eignex.klause.solver.localsearch.strategy.WalkSat

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.SolverState

/**
 * WalkSAT with CCASat-style Configuration Checking. Identical to [WalkSat] except that
 * candidate moves are first filtered to those targeting a variable whose configuration
 * has changed since its last flip — see [SolverState.boolConfChange] /
 * [SolverState.intConfChange]. This breaks short flip-unflip cycles without needing a
 * tabu tenure long enough to be globally disruptive.
 *
 * Aspiration: when every candidate is CC-blocked, the filter is dropped (mirrors the
 * existing all-tabu fallback). Tabu filtering then runs on what's left, with the same
 * aspiration. Selection is greedy on break-score with [noise]-probability random pick.
 */
class CcaWalkSat(val noise: Double = 0.5, val tabuTenure: Int = 10) : Strategy {

    override fun pickMove(state: SolverState): Move? {
        if (state.violated.isEmpty()) return null
        val factorId = state.violated.random(state.rng)
        val factor = state.problem.factors[factorId]
        state.moveSink.clear()
        factor.proposeRepairMoves(state, factorId, state.moveSink)
        val raw = state.moveSink.list
        if (raw.isEmpty()) return null

        val ccEligible = raw.filter { confChanged(state, it) }
        val afterCc = if (ccEligible.isEmpty()) raw else ccEligible

        val moves = if (tabuTenure > 0) {
            val nonTaboo = afterCc.filter { !state.isTaboo(it, tabuTenure) }
            if (nonTaboo.isEmpty()) afterCc else nonTaboo
        } else afterCc

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

    private fun confChanged(state: SolverState, move: Move): Boolean = when (move) {
        is Move.BoolFlip -> state.boolConfChange[move.varId]
        is Move.IntSet -> state.intConfChange[move.varId]
    }
}
