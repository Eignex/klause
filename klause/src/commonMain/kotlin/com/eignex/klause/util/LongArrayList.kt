package com.eignex.klause.util

/**
 * Primitive growable `Long` list — the `Long` sibling of [IntArrayList], eliminating the
 * per-element boxing a stdlib `ArrayList<Long>` pays. Use it on hot paths that accumulate raw
 * longs (bit masks, packed payloads, weights), e.g. the per-move repair-chain snapshot in
 * `LocalSearchState.buildRepairChain`.
 *
 * Backed by a doubling [LongArray]; [removeAt] / [removeValue] are O(1) swap-removes (order is
 * not preserved). Not thread-safe.
 */
internal class LongArrayList(initialCapacity: Int = 8) {
    private var data: LongArray = LongArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun add(value: Long) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Long = data[index]

    operator fun set(index: Int, value: Long) {
        data[index] = value
    }

    /** Insert [value] at [index], shifting the tail right. */
    fun insertAt(index: Int, value: Long) {
        add(0L) // grow by one (value irrelevant; overwritten by the shift)
        for (i in size - 1 downTo index + 1) data[i] = data[i - 1]
        data[index] = value
    }

    /** Swap-remove the element at [index] (O(1); does not preserve order). */
    fun removeAt(index: Int) {
        data[index] = data[--size]
    }

    /** Find the first occurrence of [value] and swap-remove it (O(1) removal, O(n) find);
     *  returns true if found. */
    fun removeValue(value: Long): Boolean {
        val d = data
        val n = size
        for (i in 0 until n) {
            if (d[i] == value) {
                d[i] = d[n - 1]
                size = n - 1
                return true
            }
        }
        return false
    }

    fun clear() {
        size = 0
    }

    /** Truncate to [newSize], dropping the suffix. No-op if [newSize] is `>= size`. */
    fun truncateTo(newSize: Int) {
        if (newSize < size) size = newSize
    }

    fun toLongArray(): LongArray = data.copyOf(size)

    /** Snapshot the current contents as a `Set<Long>` (deduplicating). Boxes — intended for
     *  occasional use, not hot loops. */
    fun toSet(): Set<Long> {
        val s = HashSet<Long>(size)
        for (i in 0 until size) s.add(data[i])
        return s
    }

    fun indexOf(value: Long): Int {
        for (i in 0 until size) if (data[i] == value) return i
        return -1
    }

    fun contains(value: Long): Boolean = indexOf(value) >= 0

    fun isEmpty(): Boolean = size == 0

    fun last(): Long = data[size - 1]

    /** Sort the live elements in place, delegating to stdlib [LongArray.sort]; [descending]
     *  reverses the result. */
    fun sort(descending: Boolean = false) {
        data.sort(0, size)
        if (descending) {
            var lo = 0
            var hi = size - 1
            while (lo < hi) {
                val t = data[lo]
                data[lo] = data[hi]
                data[hi] = t
                lo++
                hi--
            }
        }
    }

    inline fun forEach(action: (Long) -> Unit) {
        for (i in 0 until size) action(this[i])
    }
}
