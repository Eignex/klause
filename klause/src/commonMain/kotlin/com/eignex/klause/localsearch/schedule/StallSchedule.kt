package com.eignex.klause.localsearch.schedule

// Stalled after `afterStall` applied moves without a strict cost drop while still infeasible; the driver
// then enables the stall-gated move sources and the noise level switches to `stallNoise`. A weight bump
// does not reset the window, so it measures true no-progress. The round hooks are inert because the
// window needs the engine step, which only [update] carries.
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

    private val progress = ProgressWindow(trackFraction = true)

    var stalled: Boolean = false
        private set

    override var level: Double = baseNoise
        private set

    fun update(step: Long, cost: Long, fraction: Double = 0.0) {
        progress.observe(step, cost, fraction)
        stalled = afterStall > 0 && cost > 0L && progress.elapsed(step) >= afterStall
        level = if (stalled) stallNoise else baseNoise
    }

    override fun observe(cost: Long) = Unit

    override fun observe(round: RoundLog) = Unit

    override fun reset() {
        progress.reset()
        stalled = false
        level = baseNoise
    }
}
