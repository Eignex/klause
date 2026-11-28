package com.eignex.klause.portfolio

import com.eignex.klause.lp.cut.CutExchange
import com.eignex.klause.lp.cut.CutSharing
import com.eignex.klause.lp.cut.SharedCut
import com.eignex.klause.solver.result.SharingChannel
import com.eignex.kumulant.core.Concurrency
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongHashSet
import com.eignex.util.concurrent.Mutex

/**
 * Append-only, de-duplicated pool of globally valid cuts shared across the LP-bearing arms of one problem. The
 * [lock] comes from the executor's [Concurrency]. Reaching [cap] stops growth without evicting, so per-arm
 * cursors stay valid.
 */
internal class SharedCutPool(private val lock: Mutex = Mutex(concurrent = false), private val cap: Int = DEFAULT_CAP) {
    private val cuts = ArrayList<SharedCut>()
    private val keys = LongHashSet()

    private val origins = IntArrayList()

    // A cut already pooled keeps the arm that published it first.
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

    fun drainSince(cursor: Int): Drained = lock.withLock {
        val size = cuts.size
        if (cursor >= size) {
            Drained(emptyList(), emptyIntArray(), size)
        } else {
            Drained(ArrayList(cuts.subList(cursor, size)), IntArray(size - cursor) { origins[cursor + it] }, size)
        }
    }

    internal class Drained(val cuts: List<SharedCut>, val origins: IntArray, val cursor: Int)

    internal companion object {
        const val DEFAULT_CAP = 4096

        const val NO_ORIGIN = -1
    }
}

// CutSharing re-maps each cut onto the arm's relaxation, dropping any whose variables it has no column for. The
// `seen` key-set keeps this arm from re-importing a cut it published or re-exporting one twice.
internal class PoolCutExchange(
    private val pool: SharedCutPool,
    private val origin: Int = SharedCutPool.NO_ORIGIN,
    private val tally: ContributionTally? = null,
    private val meter: SharingMeter? = null,
) : CutExchange {
    private var cursor = 0
    private val seen = LongHashSet()

    override fun exchange(sharing: CutSharing) {
        val m = meter
        if (m == null) exchangeWith(sharing) else m.timed(SharingChannel.Cuts) { exchangeWith(sharing) }
    }

    private fun exchangeWith(sharing: CutSharing) {
        val drained = pool.drainSince(cursor)
        cursor = drained.cursor
        val fresh = drained.cuts.indices.filter { seen.add(drained.cuts[it].key) }
        val origins = IntArray(fresh.size) {
            drained.origins[fresh[it]].takeIf { o -> o != origin } ?: SharedCutPool.NO_ORIGIN
        }
        sharing.importCuts(fresh.map { drained.cuts[it] }, origins)
        tally?.let { t -> sharing.drainImportUses { from, uses -> t.note(Contribution.Cut, from, uses.toDouble()) } }
        val exported = sharing.exportGlobalCuts().filter { seen.add(it.key) }
        pool.publish(exported, origin)
        meter?.imported(SharingChannel.Cuts, fresh.size, drained.cursor.toLong())
        meter?.duplicates(SharingChannel.Cuts, drained.cuts.size - fresh.size)
        meter?.exported(SharingChannel.Cuts, exported.size)
    }
}
