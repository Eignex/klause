package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

/**
 * WalkSAT extended to mixed Boolean/integer moves. Pick a violated factor uniformly, ask it
 * for repair-move suggestions, then either flip a random suggestion (probability [noise]) or
 * pick the suggestion with the smallest break count (ties broken uniformly at random).
 */
class WalkSat(val noise: Double = 0.5) : Strategy {

    private val sink = MoveSink()

    override fun pickMove(state: SolverState): Move? {
        if (state.violated.isEmpty()) return null
        val factorId = state.violated.random(state.rng)
        val factor = state.problem.factors[factorId]
        sink.clear()
        factor.proposeRepairMoves(state, factorId, sink)
        val moves = sink.list
        if (moves.isEmpty()) return null

        if (state.rng.nextDouble() < noise) {
            return moves[state.rng.nextInt(moves.size)]
        }

        var bestBreak = Int.MAX_VALUE
        var bestCount = 0
        var pick: Move? = null
        for (m in moves) {
            val brk = breakCount(state, m)
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

    private fun breakCount(state: SolverState, move: Move): Int = when (move) {
        is Move.BoolFlip -> {
            var count = 0
            for (factorId in state.problem.boolOccurrences[move.varId]) {
                val f = state.problem.factors[factorId]
                if (f.isHard && f.deltaIfBoolFlipped(state, factorId, move.varId) > 0) count++
            }
            count
        }
        is Move.IntSet -> {
            var count = 0
            for (factorId in state.problem.intOccurrences[move.varId]) {
                val f = state.problem.factors[factorId]
                if (f.isHard && f.deltaIfIntSet(state, factorId, move.varId, move.newValue) > 0) count++
            }
            count
        }
    }
}
