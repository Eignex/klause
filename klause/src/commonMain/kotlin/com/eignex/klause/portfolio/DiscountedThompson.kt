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
 *
 * Thompson sampling: W. R. Thompson, "On the likelihood that one unknown probability exceeds another in view of the
 * evidence of two samples", Biometrika 25(3-4), 1933, https://doi.org/10.1093/biomet/25.3-4.285. Discounting the
 * Beta evidence follows V. Raj, S. Kalyani, "Taming non-stationary bandits: a Bayesian approach", 2017,
 * https://arxiv.org/abs/1707.09727.
 */
internal class DiscountedThompson(
    override val nbrArms: Int,
    override val random: Random,
    /** Full segments after which an observation counts half as much. */
    private val halfLife: Double,
    restrictedOnly: Boolean = false,
) : UnivariateBandit {
    init {
        require(halfLife > 0.0) { "halfLife must be > 0" }
        require(nbrArms > 0) { "nbrArms must be positive" }
    }

    private val inner = if (restrictedOnly) null else MultiArmedBandit(nbrArms, BetaBernoulliTS(PRIOR, PRIOR), random)
    private val successes = DoubleArray(nbrArms)
    private val trials = DoubleArray(nbrArms)
    private var selectorDirty = false

    override fun choose(): Int {
        val selector = checkNotNull(inner) { "restricted sampler requires candidate arms" }
        if (selectorDirty) {
            for (arm in 0 until nbrArms) {
                val stat = selector.armStat(arm)
                stat.reset()
                stat.merge(BernoulliSumResult(PRIOR + successes[arm], 2 * PRIOR + trials[arm]), 0L)
            }
            selectorDirty = false
        }
        return selector.choose()
    }

    override fun update(armIndex: Int, value: Double, weight: Double) {
        fade(2.0.pow(-weight / halfLife))
        require(armIndex in 0 until nbrArms) { "arm index out of bounds" }
        inner?.update(armIndex, value, weight)
        if (weight > 0.0 && weight.isFinite()) {
            trials[armIndex] += weight
            successes[armIndex] += value * weight
        }
    }

    override fun reset() {
        inner?.reset()
        successes.fill(0.0)
        trials.fill(0.0)
        selectorDirty = false
    }

    /** The arm among [arms], which must not be empty, that a Thompson draw over their evidence favours. */
    fun chooseAmong(arms: Collection<Int>): Int {
        require(arms.isNotEmpty()) { "no arm to choose among" }
        return arms.maxBy {
            require(it in successes.indices) { "arm index out of bounds" }
            val success = PRIOR + successes[it]
            random.nextBeta(success, 2 * PRIOR + trials[it] - success)
        }
    }

    /**
     * Keep [retained] of every arm's evidence, the prior untouched: `0` forgets everything, `1` changes nothing.
     */
    fun fade(retained: Double) {
        require(retained in 0.0..1.0) { "retained must be in [0, 1]" }
        if (retained == 1.0) return
        // Preserve the prior subtraction and rounding of the accumulator's snapshot and merge.
        for (arm in 0 until nbrArms) {
            val success = PRIOR + successes[arm]
            val total = 2 * PRIOR + trials[arm]
            successes[arm] = (PRIOR + (success - PRIOR) * retained) - PRIOR
            trials[arm] = (2 * PRIOR + (total - 2 * PRIOR) * retained) - 2 * PRIOR
        }
        selectorDirty = true
    }

    private companion object {
        // Beta(1, 1): uniform, so an arm with no evidence is as likely as any to be the best.
        const val PRIOR = 1.0
    }
}
