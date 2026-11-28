package com.eignex.klause.localsearch.schedule

/**
 * A local-search policy that retunes itself from the shared per-round feedback. A policy keeps any
 * cross-round baseline it needs itself, so one immutable [RoundLog] serves every policy observing a round.
 */
interface AdaptivePolicy {
    /** Feed the round's statistics; the policy retunes its internal state. */
    fun observe(round: RoundLog)

    /** Reset to the initial configuration. */
    fun reset()
}
