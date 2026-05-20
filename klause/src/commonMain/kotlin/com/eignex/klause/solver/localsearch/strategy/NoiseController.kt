package com.eignex.klause.solver.localsearch.strategy

import com.eignex.kumulant.stat.decay.EwmaMeanStat
import kotlin.math.sqrt

/**
 * Adaptive parameter controller in the spirit of Hoos 2002's adaptive WalkSAT noise.
 *
 * Observes the constraint-violation cost over time. When the search stalls (no improvement
 * for [theta] consecutive observations) the controller bumps [level] up toward 1.0 by
 * `(1 - level) * phi`. When the cost improves, the level decays back toward zero by
 * `(2/3) * level * phi`. The level always stays in `[minLevel, maxLevel]`.
 *
 * Two improvement-detection modes:
 *  - **Best-cost** (default, [ewmaAlpha] is null): compares cost against the all-time low
 *    seen by this controller. Strict — only ratchets improvements. Reactive to noisy
 *    cost trajectories on factor problems with mixed objective and constraint terms.
 *  - **EWMA-trend** ([ewmaAlpha] non-null): compares cost against a kumulant
 *    [EwmaMeanStat]-smoothed average. Detects "below local trend" rather than "beats
 *    all-time low" — less reactive to single-step jitter, follows drift in the cost
 *    landscape. `ewmaAlpha` ∈ (0, 1]; smaller = longer effective window. 0.05–0.2 is a
 *    reasonable starting point for problems with significant per-flip cost noise.
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
    val ewmaAlpha: Double? = null,
) {
    init {
        require(initial in minLevel..maxLevel) { "initial $initial outside [$minLevel, $maxLevel]" }
        require(theta > 0) { "theta must be positive, got $theta" }
        require(phi in 0.0..1.0) { "phi must be in [0, 1], got $phi" }
        require(ewmaAlpha == null || ewmaAlpha in 0.0..1.0) {
            "ewmaAlpha must be in (0, 1], got $ewmaAlpha"
        }
    }

    var level: Double = initial
        private set

    private val ewma: EwmaMeanStat? = ewmaAlpha?.let { EwmaMeanStat(alpha = it) }
    private var bestCostSeen: Int = Int.MAX_VALUE
    private var stallCount: Int = 0

    /** Observe the current cost; mutates [level] if the trajectory warrants. */
    fun observe(cost: Int) {
        val improving = if (ewma != null) {
            // EWMA-trend mode: update the smoother and check whether the latest cost
            // lies strictly below the smoothed average. Smoothing rejects single-step
            // jitter that the best-cost mode would treat as a non-improvement and start
            // bumping noise for.
            ewma.update(cost.toDouble(), timestampNanos = 0L, weight = 1.0)
            cost.toDouble() < ewma.read(0L).mean
        } else {
            // Best-cost mode: ratchet against the all-time low.
            val better = cost < bestCostSeen
            if (better) bestCostSeen = cost
            better
        }
        if (improving) {
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
        ewma?.reset()
    }

    companion object {
        /**
         * Derive a sensible [ewmaAlpha] from problem size and the per-restart flip budget,
         * for callers that want EWMA-mode without hand-tuning. The effective window of an
         * EWMA is roughly `1/α` observations, so the rule trades two pressures:
         *
         *  - **Bigger problems want longer windows.** Per-flip cost noise scales with the
         *    number of violated factors; a longer window rejects more of that jitter. The
         *    target window grows as `√numVars` (not linear — variance of mean-of-N is
         *    `σ²/N`, so √N is the natural rate to keep signal-to-noise constant).
         *  - **Short flip budgets want shorter windows.** A window longer than ~5% of the
         *    flip budget consumes most of the search in warm-up and never reacts. Cap the
         *    window at `flipBudget / 20`.
         *
         * The result is clipped to `[0.02, 0.5]` — α < 0.02 (window > 50) is more EWMA
         * than reactive controller; α > 0.5 (window < 2) is effectively the best-cost mode
         * with extra arithmetic.
         *
         * @param numVars total Boolean + integer variable count (`problem.numBoolVars +
         *                problem.numIntVars`).
         * @param flipBudget the per-restart flip budget the strategy will run against
         *                   (typically [LocalSearchParams.maxFlips]).
         */
        fun autoEwmaAlpha(numVars: Int, flipBudget: Int): Double {
            require(numVars >= 0) { "numVars must be non-negative, got $numVars" }
            require(flipBudget > 0) { "flipBudget must be positive, got $flipBudget" }
            val sizeWindow = sqrt(numVars.toDouble()).coerceAtLeast(5.0)
            val budgetWindow = (flipBudget / 20).coerceAtLeast(5).toDouble()
            val window = minOf(sizeWindow, budgetWindow)
            return (1.0 / window).coerceIn(0.02, 0.5)
        }
    }
}
