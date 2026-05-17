package com.eignex.klause.solver.localsearch

import kotlin.math.exp
import kotlin.random.Random

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
 *  - [SimulatedAnnealing] — Metropolis criterion: improving moves always accepted,
 *    worsening moves accepted with probability `exp((incumbent - new) / T)`; T cools
 *    over time, so late iterations behave like [Improving]. Carries its own temperature
 *    state — do not share a single instance across concurrent uses.
 */
sealed interface AcceptanceCriterion {
    fun accept(newObjective: Double, incumbentObjective: Double, rng: Random): Boolean

    data object Improving : AcceptanceCriterion {
        override fun accept(newObjective: Double, incumbentObjective: Double, rng: Random): Boolean =
            newObjective < incumbentObjective
    }

    data object BetterOrEqual : AcceptanceCriterion {
        override fun accept(newObjective: Double, incumbentObjective: Double, rng: Random): Boolean =
            newObjective <= incumbentObjective
    }

    data object RandomWalk : AcceptanceCriterion {
        override fun accept(newObjective: Double, incumbentObjective: Double, rng: Random): Boolean = true
    }

    /**
     * Metropolis acceptance with a cooling temperature schedule. On every call, the
     * temperature multiplies by [coolingRate] (floored at [minTemperature]); worsening
     * candidates are accepted with probability `exp((incumbent - new) / T)`. At high T
     * almost all moves accept (diversification); as T cools the policy converges to
     * [Improving]. Holds mutable temperature state, so each `IteratedLocalSearchRestart`
     * or `Alns` instance should get its own.
     *
     * Defaults follow common LS-SA conventions: starting T = 1.0 (problem-dependent in
     * production), cooling = 0.999, floor = 1e-3.
     */
    class SimulatedAnnealing(
        val initialTemperature: Double = 1.0,
        val coolingRate: Double = 0.999,
        val minTemperature: Double = 1e-3,
    ) : AcceptanceCriterion {
        init {
            require(initialTemperature > 0) { "initialTemperature must be positive, got $initialTemperature" }
            require(coolingRate in 0.0..1.0) { "coolingRate must be in [0, 1], got $coolingRate" }
            require(minTemperature > 0) { "minTemperature must be positive, got $minTemperature" }
        }

        var temperature: Double = initialTemperature
            private set

        override fun accept(newObjective: Double, incumbentObjective: Double, rng: Random): Boolean {
            val accepted = if (newObjective <= incumbentObjective) {
                true
            } else {
                val delta = newObjective - incumbentObjective
                rng.nextDouble() < exp(-delta / temperature)
            }
            temperature = (temperature * coolingRate).coerceAtLeast(minTemperature)
            return accepted
        }

        /** Reset the temperature to [initialTemperature]; useful for re-using one instance
         *  across consecutive `minimize` calls. */
        fun reset() {
            temperature = initialTemperature
        }
    }
}
