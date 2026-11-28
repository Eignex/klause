package com.eignex.klause.factor.scheduling.internals

import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLong

internal class CumulativeEventProfile {
    private class Event(val time: BigInt, val loadChange: BigInt)

    private val events = ArrayList<Event>()
    val repairs = CumulativeRepairCursors()
    var peak: Long? = null
        private set

    fun clear() = events.clear()

    fun addTask(start: Long, duration: Long, demand: Long) {
        if (duration <= 0 || demand <= 0) return
        val time = bigIntOf(start)
        events.add(Event(time, bigIntOf(demand)))
        events.add(Event(time + bigIntOf(duration), bigIntOf(-demand)))
    }

    // Exact event coordinates and loads keep zero overage meaningful even when a task ends beyond Long.MAX_VALUE.
    fun overage(capacity: Long, commitPeak: Boolean, checkWork: (() -> Unit)?): Int {
        events.sortWith { first, second ->
            checkWork?.invoke()
            first.time.compareTo(second.time)
        }
        val cap = bigIntOf(capacity.coerceAtLeast(0L))
        var overage = if (capacity < 0L) BIG_ZERO - bigIntOf(capacity) else BIG_ZERO
        var highest = cap
        var peakTime: Long? = null
        var usage = BIG_ZERO
        for (i in events.indices) {
            checkWork?.invoke()
            val event = events[i]
            usage += event.loadChange
            val nextTime = events.getOrNull(i + 1)?.time ?: break
            if (nextTime == event.time || usage <= cap) continue
            if (usage > highest) {
                // A new peak starts at a task start, so its time is representable as a Long.
                highest = usage
                peakTime = event.time.toLong()
            }
            if (overage < MAX_OVERAGE) {
                overage += (usage - cap) * (nextTime - event.time)
            }
        }
        if (commitPeak) peak = peakTime
        return minOf(overage, MAX_OVERAGE).toLong().toInt()
    }
}

private val MAX_OVERAGE = bigIntOf(Int.MAX_VALUE.toLong())
