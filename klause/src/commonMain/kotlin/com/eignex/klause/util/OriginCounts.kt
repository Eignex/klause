package com.eignex.klause.util

import com.eignex.util.numbers.maxOf
import com.eignex.util.numbers.toLong

// How often something imported from each source was put to use, so a source of shared clauses or cuts is measured
// by what its contributions did rather than how many it sent.
internal class OriginCounts {
    private var counts = LongArray(0)

    internal val allocatedBytes: Long get() = counts.size.toLong() * Long.SIZE_BYTES

    // A negative origin names no source.
    fun note(origin: Int) {
        if (origin < 0) return
        if (origin >= counts.size) counts = counts.copyOf(maxOf(origin + 1, counts.size * 2))
        counts[origin]++
    }

    fun drain(action: (origin: Int, uses: Long) -> Unit) {
        for (origin in counts.indices) {
            val uses = counts[origin]
            if (uses == 0L) continue
            counts[origin] = 0L
            action(origin, uses)
        }
    }
}
