package com.eignex.klause.solver.localsearch.schedule

import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.bandit.univariate.UCB1
import com.eignex.kumulant.stat.decay.EwmaMeanStat
import kotlin.math.sqrt
import kotlin.random.Random

/** Adaptive noise/cb schedule the focused-LS move selections consume: [level] in [0, 1] scales
 *  diversification (higher = more random). It is an [AdaptivePolicy] over the shared per-round
 *  feedback channel — `observe(RoundLog)` keys off the round's incumbent cost — and additionally
 *  exposes a per-step `observe(cost)` for the focused-LS strategies that retune every flip.
 *  [NoiseController] is the hand-tuned bump-on-stall implementation; [BanditNoiseController] learns
 *  which schedule profile to run (#8). */
internal interface NoiseSchedule : AdaptivePolicy {
    val level: Double
    fun observe(cost: Long)
}

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
internal class NoiseController(
    initial: Double,
    val theta: Int = 50,
    val phi: Double = 0.2,
    val minLevel: Double = 0.0,
    val maxLevel: Double = 1.0,
    val ewmaAlpha: Double? = null,
) : NoiseSchedule {
    init {
        require(initial in minLevel..maxLevel) { "initial $initial outside [$minLevel, $maxLevel]" }
        require(theta > 0) { "theta must be positive, got $theta" }
        require(phi in 0.0..1.0) { "phi must be in [0, 1], got $phi" }
        require(ewmaAlpha == null || ewmaAlpha in 0.0..1.0) {
            "ewmaAlpha must be in (0, 1], got $ewmaAlpha"
        }
    }

    override var level: Double = initial
        private set

    private val ewma: EwmaMeanStat? = ewmaAlpha?.let { EwmaMeanStat(alpha = it) }
    private var bestCostSeen: Double = Double.POSITIVE_INFINITY
    private var stallCount: Int = 0

    /** Per-step hook for the focused-LS strategies: observe the current cost; mutates [level] if
     *  the trajectory warrants. */
    override fun observe(cost: Long) = observeCost(cost.toDouble())

    /** Shared-channel hook: retune off the round's incumbent cost. */
    override fun observe(round: RoundLog) = observeCost(round.incumbentCost)

    private fun observeCost(cost: Double) {
        val improving = if (ewma != null) {
            // EWMA-trend mode: update the smoother and check whether the latest cost
            // lies strictly below the smoothed average. Smoothing rejects single-step
            // jitter that the best-cost mode would treat as a non-improvement and start
            // bumping noise for.
            ewma.update(cost, timestampNanos = 0L, weight = 1.0)
            cost < ewma.read(0L).mean
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
    override fun reset() {
        bestCostSeen = Double.POSITIVE_INFINITY
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
         *                   (typically `LocalSearchParams.maxFlips`).
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

/**
 * [NoiseSchedule] whose active profile is chosen by a kumulant bandit (#8): instead of one
 * hand-tuned bump-on-stall schedule, it runs one of several [NoiseController] profiles and, every
 * [window] observations, rewards the active profile by whether the cost improved over that window,
 * then lets a [MultiArmedBandit] pick the next. Per-session learning, so the schedule that suits
 * the instance wins out — a learned replacement for the single fixed controller.
 */
internal class BanditNoiseController(
    private val profiles: List<NoiseController>,
    private val bandit: UnivariateBandit,
    private val window: Int = 200,
) : NoiseSchedule {
    init {
        require(profiles.isNotEmpty()) { "need at least one noise profile" }
        require(window > 0) { "window must be positive, got $window" }
    }

    private var current = bandit.choose()
    private var sinceSwitch = 0
    private var windowStartCost = Long.MAX_VALUE

    override val level: Double get() = profiles[current].level

    override fun observe(cost: Long) {
        if (windowStartCost == Long.MAX_VALUE) windowStartCost = cost
        profiles[current].observe(cost)
        if (++sinceSwitch >= window) {
            bandit.update(current, if (cost < windowStartCost) 1.0 else 0.0, 1.0)
            current = bandit.choose()
            sinceSwitch = 0
            windowStartCost = cost
        }
    }

    /** Shared-channel hook: drive the active profile off the round's incumbent cost. */
    override fun observe(round: RoundLog) = observe(round.incumbentCost.toLong())

    /** Reset every profile's trajectory and the active window; the bandit keeps its learned arm
     *  values, which are session-level rather than per-restart. */
    override fun reset() {
        profiles.forEach { it.reset() }
        sinceSwitch = 0
        windowStartCost = Long.MAX_VALUE
        current = bandit.choose()
    }

    companion object {
        /** Three (aggressive / moderate / patient) bump-on-stall profiles under UCB1, all anchored
         *  to [baseline] so the strategy's baseline noise/cb is preserved. */
        fun default(baseline: Double = 0.2, seed: Long = 0L, window: Int = 200): BanditNoiseController {
            val profiles = listOf(
                NoiseController(initial = baseline, theta = 20, phi = 0.3, minLevel = baseline),
                NoiseController(initial = baseline, theta = 50, phi = 0.2, minLevel = baseline),
                NoiseController(initial = baseline, theta = 100, phi = 0.1, minLevel = baseline),
            )
            return BanditNoiseController(
                profiles,
                MultiArmedBandit(profiles.size, UCB1(alpha = 1.0), Random(seed)),
                window,
            )
        }
    }
}
