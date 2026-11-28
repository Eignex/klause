package com.eignex.klause.portfolio

import com.eignex.kumulant.core.Concurrency
import com.eignex.util.concurrent.Mutex

/**
 * What each arm's shared contributions did for the pool: a clause taking part in another arm's conflict or unit, a
 * cut selected into another arm's relaxation, a bound tightening another arm's domain, a raise of the proven
 * objective floor. Sharing is counted where it is used, never where it is published, so an arm is credited for
 * what its sharing did for others rather than for how much it sent.
 *
 * The [lock] comes from the executor's [Concurrency], as for the pools it sits beside.
 */
internal class ContributionTally(private val lock: Mutex = Mutex(concurrent = false)) {
    private val counts = Array(Contribution.entries.size) { DoubleArray(0) }

    // A negative origin names no arm and is ignored.
    fun note(kind: Contribution, origin: Int, amount: Double = 1.0) {
        if (origin < 0 || amount <= 0.0 || !amount.isFinite()) return
        lock.withLock {
            val row = counts[kind.ordinal]
            val grown = if (origin < row.size) row else row.copyOf(maxOf(origin + 1, row.size * 2))
            grown[origin] += amount
            counts[kind.ordinal] = grown
        }
    }

    fun drain(action: (kind: Contribution, origin: Int, amount: Double) -> Unit) {
        val drained = lock.withLock {
            val copy = counts.map { it.copyOf() }
            for (row in counts) row.fill(0.0)
            copy
        }
        for (kind in Contribution.entries) {
            val row = drained[kind.ordinal]
            for (origin in row.indices) if (row[origin] > 0.0) action(kind, origin, row[origin])
        }
    }
}

internal enum class Contribution(val signal: Signal) {
    Clause(Signal.ClauseUses),

    Cut(Signal.CutUses),

    Bound(Signal.BoundUses),

    // Counted by the amount the proven objective floor rose.
    Floor(Signal.Floor),
}
