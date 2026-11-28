package com.eignex.klause.localsearch.schedule

import com.eignex.klause.localsearch.RestartPolicy

/**
 * The `schedule` axis of a local-search recipe; a `null` member omits that axis.
 *
 *  - [temperature] — the annealing [Schedule].
 *  - [weights] — the violation-[WeightSchedule].
 *  - [noise] — the diversification-noise policy.
 *  - [restart] — the restart/perturbation cadence, which the engine pulls by step count rather than
 *    through [observe].
 */
data class ScheduleBundle(
    val temperature: Schedule? = null,
    val weights: WeightSchedule? = null,
    val noise: AdaptivePolicy? = null,
    val restart: RestartPolicy? = null,
) : AdaptivePolicy {
    private val adaptiveMembers: List<AdaptivePolicy> = listOfNotNull(temperature, weights, noise)

    /** Forward the round to every adaptive member. */
    override fun observe(round: RoundLog) {
        for (member in adaptiveMembers) member.observe(round)
    }

    /** Reset every adaptive member; the restart policy's reset is state-coupled and stays with the engine. */
    override fun reset() {
        for (member in adaptiveMembers) member.reset()
    }
}
