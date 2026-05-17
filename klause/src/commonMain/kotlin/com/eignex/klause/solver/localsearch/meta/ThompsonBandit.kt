package com.eignex.klause.solver.localsearch.meta

import com.eignex.kumulant.bandit.BetaBernoulliTS
import com.eignex.kumulant.bandit.MultiArmedBandit
import kotlin.random.Random

/**
 * Beta-Bernoulli Thompson-sampling bandit, kumulant-backed. Per-arm sufficient statistics
 * (successes, trials) drive a Beta posterior; [pick] samples each arm's success rate from
 * its posterior and returns the arm with the highest sample. Compared to
 * [RouletteWheelBandit]'s deterministic weight-update rule, Thompson sampling has
 * provably-optimal regret in the multi-armed bandit setting and adapts faster to non-
 * stationary rewards in practice.
 *
 * Reward semantics: callers pass `value ∈ [0, 1]` interpreted as a soft success
 * probability. ALNS's discrete rewards (newBest=3.0, accepted=1.0, rejected=0.0) should
 * be normalized to `[0, 1]` before passing; see [Alns] for the standard normalization.
 *
 * [advance] is a no-op — Thompson updates posteriors immediately on every [reward].
 * The [Bandit.pick] rng argument is ignored; kumulant's internal `RandomSequence` (seeded
 * via [randomSeed]) drives the posterior draws.
 */
class ThompsonBandit(
    override val numOperators: Int,
    val priorAlpha: Double = 1.0,
    val priorBeta: Double = 1.0,
    val randomSeed: Int = Random.Default.nextInt(),
) : Bandit {

    init { require(numOperators > 0) { "numOperators must be positive, got $numOperators" } }

    private val inner: MultiArmedBandit<*> = MultiArmedBandit(
        nbrArms = numOperators,
        policy = BetaBernoulliTS(priorAlpha = priorAlpha, priorBeta = priorBeta),
        randomSeed = randomSeed,
        maximize = true,
    )

    override fun pick(rng: Random): Int = inner.choose()

    override fun reward(operatorIdx: Int, reward: Double) {
        // BernoulliSum accepts soft probabilities in [0, 1]. Clamp to that range so
        // out-of-band rewards don't poison the posterior.
        val normalized = reward.coerceIn(0.0, 1.0)
        inner.update(operatorIdx, value = normalized, weight = 1.0)
    }

    override fun advance() {}
}
