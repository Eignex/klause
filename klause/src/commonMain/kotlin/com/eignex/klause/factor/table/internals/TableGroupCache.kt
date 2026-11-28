package com.eignex.klause.factor.table.internals

import kotlin.concurrent.Volatile

// Shared by table rows over one relation. Pruning is a pure function of (relation, column bounds), so once one
// row with full contiguous bounds sweeps without pruning, every row with the same bounds skips its sweep. A
// skipping row only defers its STR2 cleanup and never changes a domain. Written at most once with an immutable
// value; a race under parallel search recomputes the same verdict.
internal class TableGroupCache(private val arity: Int, private val tuples: LongArray, private val hi: LongArray?) {
    private class Prepared(val index: ExtensionalSupportIndex?)

    @Volatile
    private var prepared: Prepared? = null

    fun supports(poll: () -> Unit): ExtensionalSupportIndex? {
        prepared?.let { return it.index }
        val index = if (hi == null) ExtensionalSupportIndex.build(tuples, arity, poll = poll) else null
        poll()
        prepared = Prepared(index)
        return index
    }

    // Column bounds under which a full sweep prunes nothing; null until the first eligible row sets them.
    var noopMins: LongArray? = null
    var noopMaxs: LongArray? = null

    fun isNoop(mins: LongArray, maxs: LongArray): Boolean {
        val cm = noopMins ?: return false
        val cx = noopMaxs ?: return false
        if (cm.size != mins.size) return false
        for (i in mins.indices) if (cm[i] != mins[i] || cx[i] != maxs[i]) return false
        return true
    }

    fun setNoop(mins: LongArray, maxs: LongArray) {
        if (noopMins != null) return
        noopMaxs = maxs.copyOf()
        noopMins = mins.copyOf()
    }
}
