package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.localsearch.strategy.Ddfw
import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.ProbSat
import com.eignex.klause.solver.localsearch.strategy.WalkSat
import com.eignex.klause.solver.localsearch.strategy.SimulatedAnnealing

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Divide and Distribute Fixed Weights (Ishtaiwi-Thornton-Sattar-Pham 2005). Maintains a
 * per-factor weight in [LocalSearchState.factorWeights]; on every step, transfers weight from
 * the highest-weighted satisfied neighbor of each currently violated factor, conserving
 * total weight by construction. Move selection is greedy on the weighted break score
 * (Σ over factors a move would newly violate, scaled by their current weights), with a
 * small noise probability for diversification.
 *
 * Compared to [WalkSat] / [ProbSat] / [SimulatedAnnealing], DDFW *learns* which factors
 * are hard by accumulating weight on factors that stay broken across moves; the next
 * `pickMove` then prefers moves that don't break those factors. The weight-update is
 * folded into the top of `pickMove` (rather than an engine hook) — by the time DDFW is
 * asked for the next move, the previous one has already been applied.
 */
class Ddfw(
    val noiseProbability: Double = 0.05,
    val initWeight: Double = 1.0,
    val increment: Double = 1.0,
    val tabu: TabuFilter = TabuFilter.Disabled,
) : Strategy {

    private var lastUpdateStep: Long = -1L

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        if (state.step > 0 && state.step != lastUpdateStep) {
            updateWeights(state)
            lastUpdateStep = state.step
        }
        val factorId = state.violated.random(state.rng)
        val factor = state.factors[factorId]
        state.moveSink.clear()
        factor.proposeRepairMoves(state, factorId, state.moveSink)
        val raw = state.moveSink.list
        if (raw.isEmpty()) return null
        val moves = tabu.filter(state, raw)

        if (state.rng.nextDouble() < noiseProbability) {
            return moves[state.rng.nextInt(moves.size)]
        }
        var best = moves[0]
        var bestScore = weightedBreakScore(state, best)
        for (i in 1 until moves.size) {
            val s = weightedBreakScore(state, moves[i])
            if (s < bestScore) {
                best = moves[i]
                bestScore = s
            }
        }
        return best
    }

    private fun weightedBreakScore(state: LocalSearchState, move: Move): Double {
        val w = state.factorWeights
        return when (move) {
            is Move.BoolFlip -> {
                var sum = 0.0
                for (fid in state.problem.boolOccurrences[move.varId]) {
                    val f = state.factors[fid]
                    if (f.deltaIfBoolFlipped(state, fid, move.varId) > 0) sum += w[fid]
                }
                sum
            }
            is Move.IntSet -> {
                var sum = 0.0
                for (fid in state.problem.intOccurrences[move.varId]) {
                    val f = state.factors[fid]
                    if (f.deltaIfIntSet(state, fid, move.varId, move.newValue) > 0) sum += w[fid]
                }
                sum
            }
        }
    }

    private fun updateWeights(state: LocalSearchState) {
        val w = state.factorWeights
        val violated = state.violated.toIntArray()
        for (v in violated) {
            val neighbors = state.problem.factorNeighbors[v]
            var bestNeighbor = -1
            var bestWeight = -1.0
            for (n in neighbors) {
                if (state.violated.contains(n)) continue
                if (w[n] > bestWeight) {
                    bestWeight = w[n]
                    bestNeighbor = n
                }
            }
            if (bestNeighbor < 0) {
                w[v] += increment
                continue
            }
            val transfer = if (w[bestNeighbor] >= initWeight + increment) increment else increment / 2.0
            w[v] += transfer
            w[bestNeighbor] -= transfer
        }
    }
}
