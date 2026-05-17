package com.eignex.klause.solver.localsearch

/**
 * Decides whether a freshly-reached local optimum (or candidate solution) replaces the
 * incumbent it's being compared against. Used by both [IteratedLocalSearchRestart] and
 * the ALNS meta-optimizer; the same policy menu shows up in the literature.
 *
 *  - [Improving] — only strictly better candidates are accepted. Simplest, sometimes
 *    called "ILS-Better".
 *  - [BetterOrEqual] — also accepts ties; useful on plateaus.
 *  - [RandomWalk] — always accept (no rejection). Maximises diversification, simulates a
 *    pure random walk through local optima.
 */
sealed interface AcceptanceCriterion {
    fun accept(newObjective: Double, incumbentObjective: Double): Boolean

    data object Improving : AcceptanceCriterion {
        override fun accept(newObjective: Double, incumbentObjective: Double): Boolean =
            newObjective < incumbentObjective
    }

    data object BetterOrEqual : AcceptanceCriterion {
        override fun accept(newObjective: Double, incumbentObjective: Double): Boolean =
            newObjective <= incumbentObjective
    }

    data object RandomWalk : AcceptanceCriterion {
        override fun accept(newObjective: Double, incumbentObjective: Double): Boolean = true
    }
}
