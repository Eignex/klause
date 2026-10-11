package com.eignex.klause.localsearch

import kotlin.time.TimeSource

internal class LocalSearchPhaseTimer(
    private val now: () -> Long = TimeSource.Monotonic.markNow().let { origin ->
        { origin.elapsedNow().inWholeNanoseconds }
    },
) {
    private val nanos: MutableMap<String, Long> = linkedMapOf(
        "preparation" to 0L,
        "seeding" to 0L,
        "selection" to 0L,
        "application" to 0L,
        "other" to 0L,
    )
    private var phase: String = "other"
    private var running: Boolean = false
    private var last: Long = 0L

    fun enter(next: String): String {
        require(next in nanos)
        if (running) accrue()
        val previous = phase
        phase = next
        return previous
    }

    fun resume() {
        check(!running)
        last = now()
        running = true
    }

    fun pause() {
        if (!running) return
        accrue()
        running = false
    }

    fun snapshot(): Map<String, Long> {
        if (running) accrue()
        return nanos.toMap()
    }

    private fun accrue() {
        val current = now()
        val elapsed = (current - last).coerceAtLeast(0L)
        val total = nanos.getValue(phase)
        nanos[phase] = total + minOf(elapsed, Long.MAX_VALUE - total)
        last = current
    }
}

internal inline fun <T> LocalSearchPhaseTimer?.inPhase(phase: String, action: () -> T): T {
    if (this == null) return action()
    val previous = enter(phase)
    try {
        return action()
    } finally {
        enter(previous)
    }
}

internal fun <T> Sequence<T>.withActiveLocalSearchTime(timer: LocalSearchPhaseTimer?): Sequence<T> {
    if (timer == null) return this
    return Sequence {
        val source = iterator()
        object : Iterator<T> {
            override fun hasNext(): Boolean {
                timer.resume()
                try {
                    return source.hasNext()
                } finally {
                    timer.pause()
                }
            }

            override fun next(): T {
                timer.resume()
                try {
                    if (!source.hasNext()) throw NoSuchElementException()
                    return source.next()
                } finally {
                    timer.pause()
                }
            }
        }
    }
}
