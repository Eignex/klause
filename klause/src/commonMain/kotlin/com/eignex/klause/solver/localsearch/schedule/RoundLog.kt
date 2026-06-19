package com.eignex.klause.solver.localsearch.schedule

/**
 * Per-round move statistics — the signal an adaptive [Schedule] retunes against. A *round* is a
 * caller-defined batch of annealing steps (e.g. every N accepted moves). Immutable; build one with
 * a [RoundAccumulator].
 *
 * The fields are the round's raw scalar facts; cross-round judgements (e.g. "did this beat the
 * all-time best?") are left to each [AdaptivePolicy], which retains its own watermark. Per-factor
 * arrays (violated set, live weights) stay in the search state and are handed to the policies'
 * apply-methods, so the record stays immutable and shareable.
 *
 * @property proposed moves proposed this round
 * @property accepted moves accepted this round (improving or Metropolis-accepted)
 * @property costMean mean candidate cost-delta observed this round, 0.0 when nothing was recorded
 * @property costVariance population variance of the cost-deltas, 0.0 with fewer than two samples
 * @property bestCost lowest absolute cost seen this round, 0.0 when none was observed
 * @property temperature temperature in force at the round's end
 * @property incumbentCost absolute cost at the round's end (the latest observed), for trend-based
 *   policies; defaults to [bestCost]
 * @property step engine step index at the round's end, for cadence-based policies
 */
data class RoundLog(
    val proposed: Int,
    val accepted: Int,
    val costMean: Double,
    val costVariance: Double,
    val bestCost: Double,
    val temperature: Double,
    val incumbentCost: Double = bestCost,
    val step: Long = 0L,
) {
    init {
        require(proposed >= 0) { "proposed must be non-negative, got $proposed" }
        require(accepted in 0..proposed) { "accepted ($accepted) must be in 0..$proposed" }
        require(costVariance >= 0.0) { "costVariance must be non-negative, got $costVariance" }
        require(step >= 0L) { "step must be non-negative, got $step" }
    }

    /** Fraction of proposed moves accepted, in `[0, 1]`; 0.0 when nothing was proposed. */
    val acceptanceRatio: Double get() = if (proposed > 0) accepted.toDouble() / proposed else 0.0
}

/**
 * Mutable collector for one round's move statistics; [snapshot] freezes it to a [RoundLog]. Cost
 * mean/variance use Welford's online algorithm, so a long round stays overflow-safe and needs no
 * second pass. [clear] resets the counters for the next round.
 */
class RoundAccumulator {
    /** Moves proposed so far this round. */
    var proposed: Int = 0
        private set

    /** Moves accepted so far this round. */
    var accepted: Int = 0
        private set

    private var count: Int = 0
    private var mean: Double = 0.0
    private var m2: Double = 0.0
    private var best: Double = Double.POSITIVE_INFINITY
    private var last: Double = Double.NaN

    /** Record one proposed move: its [costDelta] (new − incumbent) and whether it was [accepted]. */
    fun record(costDelta: Double, accepted: Boolean) {
        proposed++
        if (accepted) this.accepted++
        count++
        val delta = costDelta - mean
        mean += delta / count
        m2 += delta * (costDelta - mean)
    }

    /** Note an absolute cost seen this round; tracks the minimum for [RoundLog.bestCost] and the
     *  latest for [RoundLog.incumbentCost]. */
    fun observeCost(cost: Double) {
        if (cost < best) best = cost
        last = cost
    }

    /** Freeze the round at [temperature] and engine [step]. Does not clear — call [clear] to start
     *  the next round. */
    fun snapshot(temperature: Double, step: Long = 0L): RoundLog {
        val bestCost = if (best.isFinite()) best else 0.0
        return RoundLog(
            proposed = proposed,
            accepted = accepted,
            costMean = if (count > 0) mean else 0.0,
            costVariance = if (count > 1) m2 / count else 0.0,
            bestCost = bestCost,
            temperature = temperature,
            incumbentCost = if (last.isNaN()) bestCost else last,
            step = step,
        )
    }

    /** Reset all counters for the next round. */
    fun clear() {
        proposed = 0
        accepted = 0
        count = 0
        mean = 0.0
        m2 = 0.0
        best = Double.POSITIVE_INFINITY
        last = Double.NaN
    }
}
