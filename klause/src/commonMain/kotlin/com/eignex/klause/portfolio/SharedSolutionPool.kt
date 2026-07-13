package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

/**
 * Cross-arm pool of feasible solutions for one minimisation portfolio — the assignment companion of
 * [SharedObjectiveBound], which shares only the bound value. Each arm publishes an improving incumbent
 * (its [Sample] and objective); the pool keeps up to [capacity] of the lowest-objective solutions it has
 * seen. Readers dive toward or improve a globally-good assignment (solution-based phasing, LNS
 * neighborhoods) rather than a worker-local one.
 *
 * Entries are held best-first (ascending objective). Solutions whose objective already appears are
 * dropped — a cheap dedup that also keeps the retained set spread across distinct objective values. The
 * [lock] comes from the executor's [Concurrency]: a no-op under the single-core executor, a platform
 * mutex under the parallel one.
 */
internal class SharedSolutionPool(private val capacity: Int = 8, private val lock: Mutex = Concurrency.None.lock()) {
    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    private class Entry(val sample: Sample, val objective: Double)

    private val entries = ArrayList<Entry>()

    /** Fold a feasible solution into the pool, keeping the [capacity] lowest-objective ones. Ignores a
     *  non-finite objective (no information) or one whose objective the pool already holds. */
    fun publish(sample: Sample, objective: Double) {
        if (!objective.isFinite()) return
        lock.withLock {
            var at = entries.size
            for (i in entries.indices) {
                if (entries[i].objective == objective) return@withLock
                if (objective < entries[i].objective) {
                    at = i
                    break
                }
            }
            if (at == entries.size && entries.size >= capacity) return@withLock
            entries.add(at, Entry(sample, objective))
            if (entries.size > capacity) entries.removeAt(entries.size - 1)
        }
    }

    /** The lowest-objective solution held, or null if the pool is empty. */
    fun best(): Sample? = lock.withLock { entries.firstOrNull()?.sample }

    /** The lowest objective held, or `+∞` if the pool is empty. */
    fun bestObjective(): Double = lock.withLock { entries.firstOrNull()?.objective ?: Double.POSITIVE_INFINITY }

    /** A snapshot of every held solution, best first. */
    fun all(): List<Sample> = lock.withLock { entries.map { it.sample } }
}
