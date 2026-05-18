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
        val raw = state.proposeMovesFromRandomViolated() ?: return null
        val moves = tabu.filter(state, raw)
        if (moves.size == 1) return moves[0]

        val cbNow = currentCb(state)
        // Compute scores upfront; under shaping (state.shapingLambda != 0) the score is
        // breakScore + shapedObjectiveDelta which can go negative when an objective
        // improvement dominates. `(eps + score)^(-cb)` is undefined for non-positive
        // bases, so shift the whole candidate set by the minimum so values stay >= 0.
        // When shaping is off, every score is breakScore (>= 0) and min is >= 0; if min
        // happens to be 0 (typical, since often some candidate doesn't break anything),
        // shift = 0 and the formula matches the original verbatim.
        val scores = DoubleArray(moves.size) { state.shapedBreakScore(moves[it]) }
        var minScore = scores[0]
        for (i in 1 until scores.size) if (scores[i] < minScore) minScore = scores[i]
        val shift = if (minScore < 0.0) -minScore else 0.0
        var totalWeight = 0.0
        val weights = DoubleArray(moves.size)
        for (i in moves.indices) {
            val w = (eps + scores[i] + shift).pow(-cbNow)
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
