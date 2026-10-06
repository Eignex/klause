package com.eignex.klause.portfolio

import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

/**
 * How often each arm's shared contributions were put to use by the arms that imported them: a clause taking part
 * in a conflict or unit, a cut selected into a relaxation, a bound tightening a domain. Counted where the
 * contribution is used, never where it is published, so an arm is credited for what its sharing did for others
 * rather than for how much it sent.
 *
 * The [lock] comes from the executor's [Concurrency], as for the pools it sits beside.
 */
internal class ContributionTally(private val lock: Mutex = Concurrency.None.lock()) {
    private val counts = Array(Contribution.entries.size) { LongArray(0) }

    /** Count [uses] of [origin]'s contributions of [kind]; a negative origin names no arm and is ignored. */
    fun note(kind: Contribution, origin: Int, uses: Long = 1L) {
        if (origin < 0 || uses <= 0L) return
        lock.withLock {
            val row = counts[kind.ordinal]
            val grown = if (origin < row.size) row else row.copyOf(maxOf(origin + 1, row.size * 2))
            grown[origin] += uses
            counts[kind.ordinal] = grown
        }
    }

    /** Hand every non-zero count to [action] and reset it. */
    fun drain(action: (kind: Contribution, origin: Int, uses: Long) -> Unit) {
        val drained = lock.withLock {
            val copy = counts.map { it.copyOf() }
            for (row in counts) row.fill(0L)
            copy
        }
        for (kind in Contribution.entries) {
            val row = drained[kind.ordinal]
            for (origin in row.indices) if (row[origin] > 0L) action(kind, origin, row[origin])
        }
    }
}

/** A kind of shared contribution one arm makes for the others. */
internal enum class Contribution(val signal: Signal) {
    /** A learned clause another arm imported. */
    Clause(Signal.ClauseUses),

    /** A global cut another arm imported. */
    Cut(Signal.CutUses),

    /** A root variable bound another arm imported. */
    Bound(Signal.BoundUses),
}
