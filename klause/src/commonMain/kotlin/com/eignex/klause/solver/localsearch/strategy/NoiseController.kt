package com.eignex.klause.solver.localsearch.strategy

/**
 * Adaptive parameter controller in the spirit of Hoos 2002's adaptive WalkSAT noise.
 *
 * Observes the constraint-violation cost over time. When the search stalls (no improvement
 * for [theta] consecutive observations) the controller bumps [level] up toward 1.0 by
 * `(1 - level) * phi`. When the cost strictly improves, the level decays back toward zero
 * by `(2/3) * level * phi`. The level always stays in `[minLevel, maxLevel]`.
 *
 * Strategies map [level] to their own knob: WalkSat sets `noise = level`, ProbSat scales
 * its `cb` exponent down, DDFW scales `increment` up. Level 0 = baseline (most greedy);
 * level toward 1 = more diversification.
 *
 * The constants follow the original paper: phi=0.2, theta scales with problem size in the
 * literature but we expose it directly so callers can tune. Default 50 is a reasonable
 * starting point for small/medium instances.
 */
class NoiseController(
    initial: Double,
    val theta: Int = 50,
    val phi: Double = 0.2,
    val minLevel: Double = 0.0,
    val maxLevel: Double = 1.0,
) {
    init {
        require(initial in minLevel..maxLevel) { "initial $initial outside [$minLevel, $maxLevel]" }
        require(theta > 0) { "theta must be positive, got $theta" }
        require(phi in 0.0..1.0) { "phi must be in [0, 1], got $phi" }
    }

    var level: Double = initial
        private set

    private var bestCostSeen: Int = Int.MAX_VALUE
    private var stallCount: Int = 0

    /** Observe the current cost; mutates [level] if the trajectory warrants. */
    fun observe(cost: Int) {
        if (cost < bestCostSeen) {
            bestCostSeen = cost
            stallCount = 0
            level = (level - (2.0 / 3.0) * level * phi).coerceIn(minLevel, maxLevel)
        } else {
            stallCount++
            if (stallCount >= theta) {
                level = (level + (1.0 - level) * phi).coerceIn(minLevel, maxLevel)
                stallCount = 0
            }
        }
    }

    /** Reset trajectory tracking — call on restart. [level] is preserved. */
    fun reset() {
        bestCostSeen = Int.MAX_VALUE
        stallCount = 0
    }
}
