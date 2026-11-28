package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.schedule.Geometric
import com.eignex.klause.localsearch.schedule.Schedule
import kotlin.math.exp
import kotlin.random.Random

/**
 * Decides whether a reached local optimum replaces the incumbent it is compared against, in
 * [IteratedLocalSearchRestart] and ALNS.
 */
sealed interface AcceptanceCriterion {
    /** True iff a candidate at [newObjective] should replace the incumbent at [incumbentObjective]. */
    fun accept(newObjective: Double, incumbentObjective: Double, rng: Random): Boolean

    /** Accept only strictly improving candidates. */
    data object Improving : AcceptanceCriterion {
        override fun accept(newObjective: Double, incumbentObjective: Double, rng: Random): Boolean =
            newObjective < incumbentObjective
    }

    /** Accept equal-or-better candidates. */
    data object BetterOrEqual : AcceptanceCriterion {
        override fun accept(newObjective: Double, incumbentObjective: Double, rng: Random): Boolean =
            newObjective <= incumbentObjective
    }

    /** Accept every candidate (pure random walk). */
    data object RandomWalk : AcceptanceCriterion {
        override fun accept(newObjective: Double, incumbentObjective: Double, rng: Random): Boolean = true
    }

    /**
     * Metropolis acceptance: improving candidates are always accepted, worsening ones with probability
     * `exp((incumbent - new) / T)`, and [schedule] advances one step per call. Holds mutable temperature
     * state, so each search needs its own instance. S. Kirkpatrick, C. D. Gelatt, M. P. Vecchi,
     * "Optimization by simulated annealing", Science 220, 1983, https://doi.org/10.1126/science.220.4598.671.
     */
    class SimulatedAnnealing(private val schedule: Schedule) : AcceptanceCriterion {
        /** Fixed geometric cooling — the default schedule. */
        constructor(
            initialTemperature: Double = 1.0,
            coolingRate: Double = 0.999,
            minTemperature: Double = 1e-3,
        ) : this(Geometric(initialTemperature, coolingRate, minTemperature))

        /** Current temperature (cools on each [accept] call). */
        val temperature: Double get() = schedule.temperature

        override fun accept(newObjective: Double, incumbentObjective: Double, rng: Random): Boolean {
            val accepted = if (newObjective <= incumbentObjective) {
                true
            } else {
                val delta = newObjective - incumbentObjective
                rng.nextDouble() < exp(-delta / schedule.temperature)
            }
            schedule.step()
            return accepted
        }

        /** Resets the schedule to its initial temperature. */
        fun reset() = schedule.reset()
    }
}
