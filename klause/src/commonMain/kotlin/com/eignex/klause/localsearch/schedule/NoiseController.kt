package com.eignex.klause.localsearch.schedule

import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.bandit.univariate.UCB1
import com.eignex.kumulant.stat.decay.EwmaMeanStat
import kotlin.math.sqrt
import kotlin.random.Random

// `level` in `[0, 1]` scales diversification. The per-step `observe(cost)` serves focused-LS strategies
// that retune every flip.
internal interface NoiseSchedule : AdaptivePolicy {
    val level: Double
    fun observe(cost: Long)
}

// After H. H. Hoos, "An adaptive noise mechanism for WalkSAT", AAAI 2002,
// https://aaai.org/papers/00655-an-adaptive-noise-mechanism-for-walksat/: `theta` observations without
// improvement raise the level by `(1 - level)·phi`; an improvement lowers it by `(2/3)·level·phi`.
// Improvement is against the all-time best, or with `ewmaAlpha` against a smoothed trend, which is less
// reactive to single-step jitter.
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

    override fun observe(cost: Long) = observeCost(cost.toDouble())

    override fun observe(round: RoundLog) = observeCost(round.incumbentCost)

    private fun observeCost(cost: Double) {
        val improving = if (ewma != null) {
            ewma.update(cost, timestampNanos = 0L, weight = 1.0)
            cost < ewma.read(0L).mean
        } else {
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

    // The level is preserved across a reset.
    override fun reset() {
        bestCostSeen = Double.POSITIVE_INFINITY
        stallCount = 0
        ewma?.reset()
    }

    companion object {
        // The EWMA window is about `1/α` observations. It grows as `√numVars`, which keeps the
        // signal-to-noise of a mean constant, capped at `flipBudget / 20` so warm-up does not consume the
        // search. Below α = 0.02 the controller stops reacting; above 0.5 it is the best-cost mode.
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

// Every `window` observations the active profile is rewarded by whether the cost improved, and the bandit
// picks the next.
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

    override fun observe(round: RoundLog) = observe(round.incumbentCost.toLong())

    // The bandit keeps its arm values: they are session-level, not per-restart.
    override fun reset() {
        profiles.forEach { it.reset() }
        sinceSwitch = 0
        windowStartCost = Long.MAX_VALUE
        current = bandit.choose()
    }

    companion object {
        // UCB1 (P. Auer, N. Cesa-Bianchi, P. Fischer, "Finite-time analysis of the multiarmed bandit
        // problem", Machine Learning 47, 2002, https://doi.org/10.1023/A:1013689704352) over three profiles
        // anchored to `baseline`.
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
