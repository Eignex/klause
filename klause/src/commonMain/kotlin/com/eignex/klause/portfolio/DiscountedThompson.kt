package com.eignex.klause.portfolio

import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.bandit.univariate.BetaBernoulliTS
import com.eignex.kumulant.bandit.univariate.MultiArmedBandit
import com.eignex.kumulant.stat.summary.BernoulliSumResult
import kotlin.math.pow
import kotlin.random.Random

/**
 * Beta-Bernoulli Thompson sampling whose evidence fades with the segments the pool runs.
 *
 * Thompson sampling explores in proportion to how plausible it still is that an arm is the best one, so an arm
 * that keeps earning nothing is tried less and less; there is no fixed exploration share for a bad arm to tax the
 * run with. Each update carries a weight, the share of its slice the segment spent, so a segment cut short counts
 * as less evidence than a full one. Before every update all evidence decays by `2^(-weight / halfLife)`: the
 * schedule keeps following an arm that stops paying, and an arm written off early gets a fresh look once its
 * evidence has faded.
 *
 * Rewards are read as Bernoulli success probabilities, so they must lie in `[0, 1]`.
 */
internal class DiscountedThompson(
    override val nbrArms: Int,
    override val random: Random,
    /** Full segments after which an observation counts half as much. */
    private val halfLife: Double,
) : UnivariateBandit {
    init {
        require(halfLife > 0.0) { "halfLife must be > 0" }
    }

    private val inner = MultiArmedBandit(nbrArms, BetaBernoulliTS(PRIOR, PRIOR), random)

    override fun choose(): Int = inner.choose()

    override fun update(armIndex: Int, value: Double, weight: Double) {
        fade(2.0.pow(-weight / halfLife))
        inner.update(armIndex, value, weight)
    }

    override fun reset() = inner.reset()

    /**
     * Keep [retained] of every arm's evidence, the prior untouched: `0` forgets everything, `1` changes nothing.
     */
    fun fade(retained: Double) {
        require(retained in 0.0..1.0) { "retained must be in [0, 1]" }
        if (retained == 1.0) return
        val evidence = inner.snapshot().map {
            BernoulliSumResult((it.successes - PRIOR) * retained, (it.trials - 2 * PRIOR) * retained)
        }
        inner.reset()
        inner.merge(evidence)
    }

    private companion object {
        // Beta(1, 1): uniform, so an arm with no evidence is as likely as any to be the best.
        const val PRIOR = 1.0
    }
}
