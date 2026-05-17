package com.eignex.klause.solver.localsearch.meta

import kotlin.random.Random

/**
 * Adaptive operator-selection bandit (Ropke-Pisinger 2006 ALNS scheme). Maintains a
 * weight per operator; on each pick, the operator is sampled with probability
 * proportional to its weight. After every [segmentLength] picks, weights update via
 *
 *     w_i ← w_i * (1 - reactionFactor) + reactionFactor * avgScore_i
 *
 * where `avgScore_i` is the mean reward per call of operator `i` over the segment
 * (clamped to [minWeight] from below to avoid permanent extinction of any operator).
 *
 * Rewards land via [reward] with the operator index returned by [pick]; the caller
 * decides the reward schedule (e.g. 3.0 = new global best, 1.0 = accepted as new
 * incumbent, 0.0 = rejected). [advance] is called once per iteration; it rolls the
 * segment counter and triggers the weight update when full.
 */
class RouletteWheelBandit(
    val numOperators: Int,
    val reactionFactor: Double = 0.1,
    val segmentLength: Int = 10,
    val initialWeight: Double = 1.0,
    val minWeight: Double = 0.01,
) {
    init {
        require(numOperators > 0) { "numOperators must be positive, got $numOperators" }
        require(reactionFactor in 0.0..1.0) { "reactionFactor must be in [0, 1], got $reactionFactor" }
        require(segmentLength > 0) { "segmentLength must be positive, got $segmentLength" }
    }

    val weights: DoubleArray = DoubleArray(numOperators) { initialWeight }
    private val accumulatedScores: DoubleArray = DoubleArray(numOperators)
    private val callCounts: IntArray = IntArray(numOperators)
    private var picksThisSegment: Int = 0

    fun pick(rng: Random): Int {
        var total = 0.0
        for (w in weights) total += w
        if (total <= 0.0) return rng.nextInt(numOperators)
        var draw = rng.nextDouble() * total
        for (i in weights.indices) {
            draw -= weights[i]
            if (draw <= 0.0) return i
        }
        return numOperators - 1
    }

    fun reward(operatorIdx: Int, reward: Double) {
        accumulatedScores[operatorIdx] += reward
        callCounts[operatorIdx]++
    }

    /** Increment the segment counter; trigger a weight update when the segment is full. */
    fun advance() {
        picksThisSegment++
        if (picksThisSegment >= segmentLength) {
            updateWeights()
            picksThisSegment = 0
        }
    }

    private fun updateWeights() {
        for (i in weights.indices) {
            if (callCounts[i] > 0) {
                val avg = accumulatedScores[i] / callCounts[i]
                weights[i] = (weights[i] * (1.0 - reactionFactor) + reactionFactor * avg)
                    .coerceAtLeast(minWeight)
            }
            accumulatedScores[i] = 0.0
            callCounts[i] = 0
        }
    }
}
