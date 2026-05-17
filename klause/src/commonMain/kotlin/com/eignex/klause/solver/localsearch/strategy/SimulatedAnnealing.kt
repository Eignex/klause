package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.ProbSat
import com.eignex.klause.solver.localsearch.strategy.WalkSat
import com.eignex.klause.solver.localsearch.strategy.SimulatedAnnealing

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.math.exp

/**
 * Temperature-annealed acceptance strategy. Picks a violated factor uniformly, asks it for
 * repair-move suggestions, then samples one and accepts by the Metropolis criterion using
 * `state.breakScore(move)` as Δ. Worsening moves are accepted with probability
 * `exp(-Δ / T)`, where `T` cools by [coolingRate] on each accepted move and is floored at
 * [minTemperature].
 *
 * Compared to [WalkSat] and [ProbSat], SA's strength is escaping local minima by drifting
 * through worse regions; the temperature schedule trades exploration (high T) for
 * exploitation (low T) over time. A short-term tabu list ([tabuTenure], default 10)
 * filters recently-touched variables to break local cycles, identical in semantics to the
 * other two strategies.
 */
class SimulatedAnnealing(
    val initialTemperature: Double = 1.0,
    val coolingRate: Double = 0.999,
    val minTemperature: Double = 0.001,
    val tabu: TabuFilter = TabuFilter(tenure = 10),
) : Strategy {

    private var temperature: Double = initialTemperature

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        val factorId = state.violated.random(state.rng)
        val factor = state.factors[factorId]
        state.moveSink.clear()
        factor.proposeRepairMoves(state, factorId, state.moveSink)
        val raw = state.moveSink.list
        if (raw.isEmpty()) return null
        val moves = tabu.filter(state, raw)

        repeat(moves.size) {
            val move = moves[state.rng.nextInt(moves.size)]
            val delta = state.breakScore(move)
            if (delta <= 0 || state.rng.nextDouble() < exp(-delta.toDouble() / temperature)) {
                anneal()
                return move
            }
        }
        anneal()
        return moves[state.rng.nextInt(moves.size)]
    }

    private fun anneal() {
        temperature = (temperature * coolingRate).coerceAtLeast(minTemperature)
    }
}
