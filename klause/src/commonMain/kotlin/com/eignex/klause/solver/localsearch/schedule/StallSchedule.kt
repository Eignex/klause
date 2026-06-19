package com.eignex.klause.solver.localsearch.schedule

/**
 * The CBLS plateau-escape stall signal as a schedule-axis member: it tracks the no-strict-cost-drop
 * window off the engine step counter and exposes two things the driver consumes each pick —
 *
 *  - [stalled]: whether the search has gone [afterStall] applied moves with no strict cost drop while
 *    still infeasible. The driver gates the plateau-escape sources (frontier / stall swaps / ejection
 *    chains, the [com.eignex.klause.solver.localsearch.movesource.ConfiguredSource.stallGated] ones)
 *    on this, so they broaden the pool only once the in-place repair pool has trapped the search.
 *  - [level]: the diversification-noise level for the acceptance rule — [baseNoise] normally,
 *    [stallNoise] while stalled (the hotter random walk that steps uphill out of a basin). As a
 *    [NoiseSchedule] it steers the WalkSAT-style noise of the acceptance axis exactly like the
 *    adaptive controllers.
 *
 * The window resets on a strict cost drop and on a restart (rewound step); it is *not* reset by a
 * weight bump, so it measures true no-progress, distinct from the weight schedule's own stall. The
 * driver advances it once per pick via [update]; the [NoiseSchedule.observe] hooks are inert (the
 * stall window needs the step, which only [update] carries).
 */
internal class StallSchedule(
    private val afterStall: Int,
    private val baseNoise: Double,
    private val stallNoise: Double,
) : NoiseSchedule {
    init {
        require(afterStall >= 0) { "afterStall ≥ 0, got $afterStall" }
        require(baseNoise in 0.0..1.0) { "baseNoise ∈ [0, 1], got $baseNoise" }
        require(stallNoise in 0.0..1.0) { "stallNoise ∈ [0, 1], got $stallNoise" }
    }

    private var lastSeenStep: Long = -1L
    private var lastDropStep: Long = 0L
    private var lastCost: Long = Long.MAX_VALUE

    /** Whether the search is currently stalled (set by [update]). */
    var stalled: Boolean = false
        private set

    override var level: Double = baseNoise
        private set

    /** Advance the stall window to the engine's `(step, cost)`, recomputing [stalled] and [level]. */
    fun update(step: Long, cost: Long) {
        if (step < lastSeenStep) {
            lastDropStep = step
            lastCost = cost
            lastSeenStep = step
        } else if (step != lastSeenStep) {
            if (cost < lastCost) {
                lastCost = cost
                lastDropStep = step
            }
            lastSeenStep = step
        }
        stalled = afterStall > 0 && cost > 0L && step - lastDropStep >= afterStall
        level = if (stalled) stallNoise else baseNoise
    }

    /** Inert: the stall window needs the engine step, which only [update] carries. */
    override fun observe(cost: Long) = Unit

    /** Inert: the stall window is driven per pick via [update], not the round channel. */
    override fun observe(round: RoundLog) = Unit

    override fun reset() {
        lastSeenStep = -1L
        lastDropStep = 0L
        lastCost = Long.MAX_VALUE
        stalled = false
        level = baseNoise
    }
}
