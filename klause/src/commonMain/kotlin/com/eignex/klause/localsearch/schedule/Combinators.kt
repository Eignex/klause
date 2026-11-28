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
 * Runs [Segment] sub-schedules back to back, resetting each leg as it starts so it begins at its own start
 * temperature. After the final leg the sequence holds it, or wraps to the first when [loop] is set.
 * [reheat] and [observe] go to the active leg.
 */
class SequenceSchedule(
    private val segments: List<Segment>,
    /** Whether to wrap back to the first leg after the last instead of holding it. */
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
        if (onLast && !loop) return
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

/** Cycles [Segment] sub-schedules indefinitely: a [SequenceSchedule] with `loop = true`. */
class LoopSchedule(segments: List<Segment>) : Schedule by SequenceSchedule(segments, loop = true)
