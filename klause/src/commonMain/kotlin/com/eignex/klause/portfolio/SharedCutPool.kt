package com.eignex.klause.portfolio

import com.eignex.klause.lp.cut.CutExchange
import com.eignex.klause.lp.cut.CutSharing
import com.eignex.klause.lp.cut.SharedCut
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongHashSet
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

/**
 * An append-only, de-duplicated pool of portfolio-portable [SharedCut]s shared across the LP-bearing
 * arms of one `Problem` — the cut analogue of [SharedClausePool]. Every
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
    private val keys = LongHashSet()

    // The arm that published each cut, parallel to [cuts]; [NO_ORIGIN] when the publisher named none.
    private val origins = IntArrayList()

    /** Append the unseen cuts of [batch] (by key) as published by arm [origin], up to [cap]. A cut already
     *  pooled keeps the arm that published it first. */
    fun publish(batch: List<SharedCut>, origin: Int = NO_ORIGIN) {
        if (batch.isEmpty()) return
        lock.withLock {
            for (c in batch) {
                if (cuts.size >= cap) break
                if (keys.add(c.key)) {
                    cuts.add(c)
                    origins.add(origin)
                }
            }
        }
    }

    /** The cuts appended at index ≥ [cursor], paired with the new cursor (the current size). */
    fun drainSince(cursor: Int): Drained = lock.withLock {
        val size = cuts.size
        if (cursor >= size) {
            Drained(emptyList(), EmptyIntArray, size)
        } else {
            Drained(ArrayList(cuts.subList(cursor, size)), IntArray(size - cursor) { origins[cursor + it] }, size)
        }
    }

    /** A drained batch, the arm that published each of its cuts, and the advanced cursor. */
    internal class Drained(val cuts: List<SharedCut>, val origins: IntArray, val cursor: Int)

    internal companion object {
        const val DEFAULT_CAP = 4096

        /** The origin of a cut whose publisher named no arm. */
        const val NO_ORIGIN = -1
    }
}

/**
 * A per-arm [CutExchange] over a [SharedCutPool]: on each [exchange] it imports the cuts published since
 * this arm last looked and exports the arm's own, both through the arm's [CutSharing] (which re-maps each
 * cut onto the arm's relaxation, dropping any whose variables it has no column for). A `seen` key-set
 * holds every key this arm has already imported or exported, so it never re-imports a cut it published
 * nor re-exports one twice; the pool de-dups globally on top.
 */
internal class PoolCutExchange(
    private val pool: SharedCutPool,
    /** The arm this exchange publishes for; see [SharedCutPool.publish]. */
    private val origin: Int = SharedCutPool.NO_ORIGIN,
    /** Where selections of imported cuts are counted for the arms that published them; null counts nothing. */
    private val tally: ContributionTally? = null,
) : CutExchange {
    private var cursor = 0
    private val seen = LongHashSet()

    override fun exchange(sharing: CutSharing) {
        val drained = pool.drainSince(cursor)
        cursor = drained.cursor
        val fresh = drained.cuts.indices.filter { seen.add(drained.cuts[it].key) }
        val origins = IntArray(fresh.size) {
            drained.origins[fresh[it]].takeIf { o -> o != origin } ?: SharedCutPool.NO_ORIGIN
        }
        sharing.importCuts(fresh.map { drained.cuts[it] }, origins)
        tally?.let { t -> sharing.drainImportUses { from, uses -> t.note(Contribution.Cut, from, uses) } }
        val exported = sharing.exportGlobalCuts().filter { seen.add(it.key) }
        pool.publish(exported, origin)
    }
}
