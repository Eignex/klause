package com.eignex.klause.portfolio

import com.eignex.klause.lp.cut.CutExchange
import com.eignex.klause.lp.cut.CutSharing
import com.eignex.klause.lp.cut.SharedCut
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

/**
 * An append-only, de-duplicated pool of portfolio-portable [SharedCut]s shared across the LP-bearing
 * arms of one [com.eignex.klause.solver.Problem] — the cut analogue of [SharedClausePool] (#809). Every
 * pooled cut is globally valid, so an arm may fold any of them into its relaxation soundly; arms publish
 * the global cuts they harvest and pull others' via a per-arm cursor ([PoolCutExchange]), so a cut one
 * arm finds tightens every arm.
 *
 * The [lock] comes from the executor's [Concurrency] (a no-op under the single-core sequential executor,
 * a platform mutex under the parallel one). De-dup is by [SharedCut.key]; a bounded [cap] stops growth
 * (it never evicts, so per-arm cursors stay valid).
 */
internal class SharedCutPool(private val lock: Mutex = Concurrency.None.lock(), private val cap: Int = DEFAULT_CAP) {
    private val cuts = ArrayList<SharedCut>()
    private val keys = HashSet<Long>()

    /** Append the unseen cuts of [batch] (by key), up to [cap]. */
    fun publish(batch: List<SharedCut>) {
        if (batch.isEmpty()) return
        lock.withLock {
            for (c in batch) {
                if (cuts.size >= cap) break
                if (keys.add(c.key)) cuts.add(c)
            }
        }
    }

    /** The cuts appended at index ≥ [cursor], paired with the new cursor (the current size). */
    fun drainSince(cursor: Int): Drained = lock.withLock {
        val size = cuts.size
        if (cursor >= size) Drained(emptyList(), size) else Drained(ArrayList(cuts.subList(cursor, size)), size)
    }

    /** A drained batch + the advanced cursor. */
    internal class Drained(val cuts: List<SharedCut>, val cursor: Int)

    internal companion object {
        const val DEFAULT_CAP = 4096
    }
}

/**
 * A per-arm [CutExchange] over a [SharedCutPool]: on each [exchange] it imports the cuts published since
 * this arm last looked and exports the arm's own, both through the arm's [CutSharing] (which re-maps each
 * cut onto the arm's relaxation, dropping any whose variables it has no column for). A `seen` key-set
 * holds every key this arm has already imported or exported, so it never re-imports a cut it published
 * nor re-exports one twice; the pool de-dups globally on top.
 */
internal class PoolCutExchange(private val pool: SharedCutPool) : CutExchange {
    private var cursor = 0
    private val seen = HashSet<Long>()

    override fun exchange(sharing: CutSharing) {
        val drained = pool.drainSince(cursor)
        cursor = drained.cursor
        sharing.importCuts(drained.cuts.filter { seen.add(it.key) })
        val fresh = sharing.exportGlobalCuts().filter { seen.add(it.key) }
        pool.publish(fresh)
    }
}
