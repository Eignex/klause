package com.eignex.klause.bench.metric

/*
 * Per-case monotonic elapsed observations, accumulating nanoseconds before whole-millisecond output.
 * Nested phases are inclusive: do not sum them. An absent phase was not observed.
 */
internal class HarnessTimings(private val nanoTime: () -> Long = System::nanoTime) {
    private val durations = linkedMapOf<String, Long>()

    fun start(): Long = nanoTime()

    fun finish(phase: String, started: Long) {
        durations[phase] = durations.getOrDefault(phase, 0) + (nanoTime() - started).coerceAtLeast(0)
    }

    fun <T> measure(phase: String, block: () -> T): T {
        val started = start()
        try {
            return block()
        } finally {
            finish(phase, started)
        }
    }

    fun stats(): Map<String, String> = durations.mapValues { (_, nanos) -> (nanos / 1_000_000).toString() }
}
