package com.eignex.klause.localsearch.schedule

/**
 * One leg of a [SequenceSchedule]: run [schedule] for [steps] steps, then the sequence advances to
 * the next leg.
 *
 * @property schedule the sub-schedule governing this leg's temperature regime
 * @property steps number of [Schedule.step] calls this leg lasts before the sequence advances
 */
data class Segment(val schedule: Schedule, val steps: Int) {
    init {
        require(steps > 0) { "steps must be positive, got $steps" }
    }
}

/**
 * Runs a list of [Segment] sub-schedules back to back, so different phases of one search use
 * different temperature regimes — e.g. a hot, fast-cooling exploratory leg followed by a cool,
 * slow-cooling exploitative leg. Each [step] advances the active leg's schedule; once a leg has
 * taken its [Segment.steps] steps, the sequence moves to the next leg and **resets** it, so each leg
 * begins at its own start temperature.
 *
 * After the final leg's budget is spent the sequence stays on it (continuing to [step] it) when
 * [loop] is false, or wraps back to the first leg — resetting it — when [loop] is true. [reheat] and
 * [observe] are forwarded to the active leg; [reset] returns to the first leg and resets every leg.
 */
class SequenceSchedule(
    private val segments: List<Segment>,
    /** Whether to wrap back to the first leg after the last (cycling) instead of holding the last. */
    val loop: Boolean = false,
) : Schedule {
    init {
        require(segments.isNotEmpty()) { "need at least one segment" }
    }

    private var index: Int = 0
    private var stepsInSegment: Int = 0

    private val active: Schedule get() = segments[index].schedule

    override val temperature: Double get() = active.temperature

    override fun step() {
        active.step()
        stepsInSegment++
        if (stepsInSegment >= segments[index].steps) advance()
    }

    private fun advance() {
        val onLast = index == segments.size - 1
        if (onLast && !loop) return // hold the final leg, continuing to cool it
        index = if (onLast) 0 else index + 1
        stepsInSegment = 0
        active.reset()
    }

    override fun reheat(factor: Double) = active.reheat(factor)

    override fun observe(round: RoundLog) = active.observe(round)

    override fun reset() {
        for (segment in segments) segment.schedule.reset()
        index = 0
        stepsInSegment = 0
    }
}

/**
 * Cycles a list of [Segment] sub-schedules indefinitely: identical to a [SequenceSchedule] with
 * `loop = true`, wrapping back to the first leg (and resetting it) after the last. Useful for a
 * repeating explore/exploit cadence over a long run.
 */
class LoopSchedule(segments: List<Segment>) : Schedule by SequenceSchedule(segments, loop = true)
