package com.eignex.klause.util

/**
 * Counts per origin: how often something imported from each source was put to use where it landed. Used for
 * shared clauses and cuts, so a source is measured by what its contributions did rather than by how many it sent.
 */
internal class OriginCounts {
    private var counts = LongArray(0)

    /** Count one use for [origin]; a negative origin, one naming no source, is ignored. */
    fun note(origin: Int) {
        if (origin < 0) return
        if (origin >= counts.size) counts = counts.copyOf(maxOf(origin + 1, counts.size * 2))
        counts[origin]++
    }

    /** Hand every non-zero count to [action] and reset it. */
    fun drain(action: (origin: Int, uses: Long) -> Unit) {
        for (origin in counts.indices) {
            val uses = counts[origin]
            if (uses == 0L) continue
            counts[origin] = 0L
            action(origin, uses)
        }
    }
}
