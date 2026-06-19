package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.localsearch.schedule.Geometric
import com.eignex.klause.solver.localsearch.schedule.Schedule
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
     * Metropolis acceptance under a temperature [schedule] (advanced one step per call): improving
     * candidates always accepted, worsening candidates accepted with probability
     * `exp((incumbent - new) / T)` where T is the schedule's current temperature. At high T almost
     * all moves accept (diversification); as T cools the policy converges to [Improving]. Holds the
     * schedule's mutable temperature state, so each `IteratedLocalSearchRestart` or `Alns` instance
     * should get its own.
     *
     * The default [Geometric] schedule (start 1.0, cooling 0.999, floor 1e-3) is pure cool-only;
     * pass an adaptive-cooling or reheating schedule for a stronger trajectory.
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

        /** Reset the schedule to its initial temperature; useful for re-using one instance
         *  across consecutive `minimize` calls. */
        fun reset() = schedule.reset()
    }
}
