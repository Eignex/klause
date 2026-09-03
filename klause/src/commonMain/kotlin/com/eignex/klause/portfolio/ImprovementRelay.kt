package com.eignex.klause.portfolio

import com.eignex.klause.util.MutableLongObjectMap
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

/**
 * Hands concurrently published improvements to one consumer **in the order the shared incumbent installed
 * them**, which is the order in which they strictly improve.
 *
 * The installs themselves are already monotone — `IncumbentExchange.offer` refuses a non-improving candidate
 * before its compare-and-set, so exactly one publisher wins each version. What is not ordered is the fan-in:
 * a publisher can lose the CPU between the CAS that installed its version and the call that reports it, so
 * two winners can reach the consumer the other way round and a consumer scoring each element against the
 * previous one reads a regression that never happened. The relay orders on the installed
 * [com.eignex.klause.solver.incumbent.VerifiedIncumbent.version] instead of on arrival: an improvement that
 * overtook its predecessor waits here, and the publisher that closes the gap delivers whatever was waiting
 * on it. Publishing stays outside the lock, so the shared bound still tightens for every arm the instant it
 * is installed, whatever the consumer is doing with the improvement before it.
 *
 * [deliver] runs the consumer under [lock] and one version at a time, so it is also the serialisation an
 * executor fanning in from real threads needs; the [Concurrency.None] default leaves it free for a
 * single-writer executor.
 *
 * The consumer is taken per call because a fan-in gives each producer its own emit function — every one of
 * them must feed the same consumer.
 */
internal class ImprovementRelay(private val lock: Mutex = Concurrency.None.lock()) {
    private val waiting = MutableLongObjectMap<AttributedImprovement>()
    private var next = FIRST_VERSION

    /** Deliver [improvement], installed as [version], once every version before it has been delivered. */
    fun deliver(version: Long, improvement: AttributedImprovement, consume: (AttributedImprovement) -> Unit) {
        lock.withLock {
            require(version >= next) { "version $version was already delivered; the next one due is $next" }
            waiting.put(version, improvement)
            var due = waiting[next]
            while (due != null) {
                waiting.remove(next)
                next++
                consume(due)
                due = waiting[next]
            }
        }
    }

    private companion object {
        /** Exchange versions count from 1 and skip nothing, so the first install is the first delivery. */
        const val FIRST_VERSION = 1L
    }
}
