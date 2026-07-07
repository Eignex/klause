package com.eignex.klause.portfolio

import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

/**
 * Cross-arm **globally-valid** decision-level-0 integer-variable bound tightenings of one portfolio — the
 * variable-domain companion of [SharedObjectiveBound]. Each arm publishes the root bounds it proves hold
 * at *every* solution (root propagation, the variable-shaving deductions), and the manager keeps their
 * intersection: the tightest lower bound and tightest upper bound any arm has shown for each variable.
 * A peer imports them at its own level 0, so a tightening one arm proves (e.g. by shaving) reaches arms
 * that did not run that work.
 *
 * Only **unconditional** tightenings cross here — bounds proven infeasible regardless of the incumbent.
 * Incumbent-relative reduced-cost fixings are deliberately *not* shared: they are valid only against the
 * cutoff they were derived under, every LP arm already derives its own against the shared incumbent, and
 * transferring them across arms would need cutoff-keyed retraction that risks an unsound prune for no new
 * deduction. The intersection here is monotone and every entry holds at every solution, so importing one
 * only ever soundly tightens a domain.
 *
 * The [lock] comes from the executor's [Concurrency]: a no-op under the single-core sequential executor,
 * a platform mutex under the parallel one.
 */
internal class SharedVarBounds(numIntVars: Int, private val lock: Mutex = Concurrency.None.lock()) {
    private val lo = LongArray(numIntVars) { Long.MIN_VALUE }
    private val hi = LongArray(numIntVars) { Long.MAX_VALUE }

    /** Tighten the shared bounds of [varId] toward `[lower, upper]` (keeps the tightest seen each side). */
    fun publish(varId: Int, lower: Long, upper: Long) {
        if (varId !in lo.indices) return
        lock.withLock {
            if (lower > lo[varId]) lo[varId] = lower
            if (upper < hi[varId]) hi[varId] = upper
        }
    }

    /** The tightest shared lower bound for [varId] (`Long.MIN_VALUE` if none). */
    fun lowerOf(varId: Int): Long = lock.withLock { if (varId in lo.indices) lo[varId] else Long.MIN_VALUE }

    /** The tightest shared upper bound for [varId] (`Long.MAX_VALUE` if none). */
    fun upperOf(varId: Int): Long = lock.withLock { if (varId in hi.indices) hi[varId] else Long.MAX_VALUE }
}
