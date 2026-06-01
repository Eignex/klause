package com.eignex.klause.solver.localsearch

/**
 * Weighting policy for how [IteratedLocalSearchRestart]'s crossover combines two parent
 * samples on a per-variable basis. The crossover walks each variable and picks parent A's
 * or B's value; this interface decides the probability of picking parent A given the
 * parents' objective values (lower = better, optimizer minimises).
 *
 *  - [Uniform] flips a fair coin per variable; mixed offspring with maximum diversity.
 *  - [BetterBiased] tilts the coin toward the parent with the lower objective. Bias
 *    rate `r ∈ [0, 0.5]` controls how strongly: 0.0 reduces to Uniform, 0.5 always
 *    picks the better parent (an elitist 1-parent restart in disguise). 0.1-0.2 is a
 *    reasonable starting point.
 */
sealed interface CrossoverBias {
    /** Probability of taking parent A's value at a crossover position. */
    fun probParentA(parentAObjective: Double, parentBObjective: Double): Double

    /** Unbiased 50/50 crossover. */
    data object Uniform : CrossoverBias {
        override fun probParentA(parentAObjective: Double, parentBObjective: Double): Double = 0.5
    }

    data class BetterBiased(
        /** Bias strength toward the better parent, in `[0, 0.5]`. */
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
