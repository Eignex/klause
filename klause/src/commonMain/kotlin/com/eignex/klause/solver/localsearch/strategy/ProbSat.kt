package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.math.pow

/**
 * Smooth-scoring local-search strategy in the spirit of Balint & Schöning 2012's "probSAT".
 * Picks among proposed repair candidates with probability `(epsilon + brk)^(-cb)` where `brk`
 * is the candidate's [LocalSearchState.breakScore]: candidates that break few currently-satisfied
 * factors get exponentially more weight than ones that break many. The continuous weighting
 * scales more gracefully than [WalkSat]'s binary noise/greedy split, especially on factor
 * problems with mixed degree.
 *
 * Defaults follow the paper: cb=2.06, eps=1.0. cb < 0 inverts the bias (avoid for production).
 */
open class ProbSat(
    val cb: Double = 2.06,
    val eps: Double = 1.0,
    val tabu: TabuFilter = TabuFilter(tenure = 10),
) : Strategy {

    /** Subclass hook for parameter scheduling; [AdaptiveProbSat] overrides to vary `cb`
     *  with the cost trajectory. */
    protected open fun currentCb(state: LocalSearchState): Double = cb

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        val factorId = state.violated.random(state.rng)
        val factor = state.factors[factorId]
        state.moveSink.clear()
        factor.proposeRepairMoves(state, factorId, state.moveSink)
        val raw = state.moveSink.list
        if (raw.isEmpty()) return null
        val moves = tabu.filter(state, raw)
        if (moves.size == 1) return moves[0]

        val cbNow = currentCb(state)
        var totalWeight = 0.0
        val weights = DoubleArray(moves.size)
        for (i in moves.indices) {
            val brk = state.breakScore(moves[i])
            val w = (eps + brk).pow(-cbNow)
            weights[i] = w
            totalWeight += w
        }
        if (totalWeight == 0.0) return moves[state.rng.nextInt(moves.size)]
        var draw = state.rng.nextDouble() * totalWeight
        for (i in moves.indices) {
            draw -= weights[i]
            if (draw <= 0.0) return moves[i]
        }
        return moves[moves.size - 1]
    }
}
