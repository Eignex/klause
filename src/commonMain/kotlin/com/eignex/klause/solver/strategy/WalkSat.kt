package com.eignex.klause.solver.strategy

import com.eignex.klause.solver.SolverState

/**
 * Selman-Kautz-Cohen WalkSAT: pick a violated factor uniformly, then either pick the variable
 * with smallest break count (ties broken uniformly at random) or, with probability [noise],
 * flip a random variable from the factor.
 */
class WalkSat(val noise: Double = 0.5) : Strategy {

    override fun pickFlip(state: SolverState): Int {
        if (state.violated.isEmpty()) return -1
        val factorId = state.violated.random(state.rng)
        val factor = state.problem.factors[factorId]
        val vars = factor.variables
        if (vars.isEmpty()) return -1

        if (state.rng.nextDouble() < noise) {
            return vars[state.rng.nextInt(vars.size)]
        }

        var bestBreak = Int.MAX_VALUE
        var bestCount = 0
        var pick = -1
        for (v in vars) {
            val brk = breakCount(state, v)
            if (brk < bestBreak) {
                bestBreak = brk
                bestCount = 1
                pick = v
            } else if (brk == bestBreak) {
                bestCount++
                if (state.rng.nextInt(bestCount) == 0) pick = v
            }
        }
        return pick
    }

    private fun breakCount(state: SolverState, variable: Int): Int {
        var count = 0
        for (factorId in state.problem.occurrences[variable]) {
            val f = state.problem.factors[factorId]
            if (!f.isHard) continue
            if (f.deltaIfFlipped(state, factorId, variable) > 0) count++
        }
        return count
    }
}
