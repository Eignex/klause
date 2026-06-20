package com.eignex.klause.solver.localsearch.schedule

import com.eignex.klause.solver.localsearch.RestartPolicy

/**
 * The `schedule` axis of a local-search recipe: the bundle of policies that govern a search's
 * tempo, all driven off the single per-round feedback channel.
 *
 *  - [temperature] — the annealing [Schedule] (cooling / reheating / combinators).
 *  - [weights] — the violation-[WeightSchedule] (bump-on-stall + relax toward the seed).
 *  - [noise] — the diversification-noise policy (a `NoiseController`, or any [AdaptivePolicy]).
 *  - [restart] — the restart/perturbation cadence.
 *
 * One [RoundAccumulator] snapshot drives every *adaptive* member each round via [observe], and
 * [reset] resets them together (e.g. on restart). The restart cadence is held as-is: the engine
 * pulls it by step count — the same signal [RoundLog.step] carries — so it participates as the
 * bundle's cadence member without being re-plumbed through `observe`. Any member may be `null`,
 * making a recipe that simply omits that axis (e.g. a pure greedy arm with no temperature).
 */
data class ScheduleBundle(
    val temperature: Schedule? = null,
    val weights: WeightSchedule? = null,
    val noise: AdaptivePolicy? = null,
    val restart: RestartPolicy? = null,
) : AdaptivePolicy {
    private val adaptiveMembers: List<AdaptivePolicy> = listOfNotNull(temperature, weights, noise)

    /** Fan the round out to every adaptive member, so one feedback snapshot retunes them all. */
    override fun observe(round: RoundLog) {
        for (member in adaptiveMembers) member.observe(round)
    }

    /** Reset every adaptive member. The restart policy's reset is state-coupled and stays with the
     *  engine, so it is not driven here. */
    override fun reset() {
        for (member in adaptiveMembers) member.reset()
    }
}
