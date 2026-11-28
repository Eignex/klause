package com.eignex.klause.localsearch

/**
 * Probability that [IteratedLocalSearchRestart]'s crossover takes parent A's value for a variable, given both
 * parents' objectives (lower is better).
 */
sealed interface CrossoverBias {
    /** Probability of taking parent A's value at a crossover position. */
    fun probParentA(parentAObjective: Double, parentBObjective: Double): Double

    /** Unbiased 50/50 crossover. */
    data object Uniform : CrossoverBias {
        override fun probParentA(parentAObjective: Double, parentBObjective: Double): Double = 0.5
    }

    /** Crossover biased toward the better-objective parent. */
    data class BetterBiased(
        /** Bias toward the better parent in `[0, 0.5]`: 0 is uniform, 0.5 always takes the better parent. */
        val rate: Double = 0.2,
    ) : CrossoverBias {
        init {
            require(rate in 0.0..0.5) { "BetterBiased rate must be in [0, 0.5], got $rate" }
        }
        override fun probParentA(parentAObjective: Double, parentBObjective: Double): Double = when {
            parentAObjective < parentBObjective -> 0.5 + rate
            parentAObjective > parentBObjective -> 0.5 - rate
            else -> 0.5
        }
    }
}
