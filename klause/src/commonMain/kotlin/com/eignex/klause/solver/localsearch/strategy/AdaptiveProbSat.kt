package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Adaptive variant of [ProbSat]. The break-exponent `cb` starts at [baselineCb] and is
 * steered down during stalls (more diversification: distribution flattens toward uniform)
 * and back up on improvement (sharper preference for low-break moves).
 *
 * The [NoiseController]'s `level` maps to an exponent multiplier:
 *   `cb = baselineCb * (1 - level * 0.5)`
 * so at level 0 we run the paper defaults; at level 1 we run cb at half its baseline.
 *
 * Subclasses [ProbSat] and overrides only the cb hook; tabu / weighted draw / eps come
 * from the parent unchanged.
 */
class AdaptiveProbSat(
    val baselineCb: Double = 2.06,
    eps: Double = 1.0,
    tabu: TabuFilter = TabuFilter(tenure = 10),
    theta: Int = 50,
    phi: Double = 0.2,
) : ProbSat(cb = baselineCb, eps = eps, tabu = tabu) {

    private val controller = NoiseController(initial = 0.0, theta = theta, phi = phi)

    val currentCb: Double get() = baselineCb * (1.0 - controller.level * 0.5)

    override fun currentCb(state: LocalSearchState): Double {
        controller.observe(state.cost)
        return currentCb
    }
}
