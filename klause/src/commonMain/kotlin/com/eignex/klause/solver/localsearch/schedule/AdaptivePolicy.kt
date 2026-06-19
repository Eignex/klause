package com.eignex.klause.solver.localsearch.schedule

/**
 * Anything that retunes itself from the shared per-round feedback channel. The single `observe`
 * surface every adaptive local-search policy implements — temperature schedules ([Schedule]),
 * violation-weight schedules, noise controllers, restart/perturbation cadence — so one
 * [RoundAccumulator] snapshot drives them all each round.
 *
 * A policy retains whatever cross-round baseline it needs (e.g. an all-time-best watermark); the
 * [RoundLog] carries only the round's raw facts, so it stays immutable and shareable across every
 * policy observing the same round.
 */
interface AdaptivePolicy {
    /** Feed the round's statistics; the policy retunes its internal state. */
    fun observe(round: RoundLog)

    /** Reset to the initial configuration (e.g. on restart). */
    fun reset()
}
