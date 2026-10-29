package com.eignex.klause.util

/**
 * Primitive growable Long list backed by a doubling [LongArray]. [removeAt] and
 * [removeValue] swap-remove without preserving order. Not thread-safe.
 */
internal class LongArrayList(initialCapacity: Int = 8) {
    private var data: LongArray = LongArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    internal val allocatedBytes: Long get() = data.size.toLong() * Long.SIZE_BYTES

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
        add(0L)
        for (i in size - 1 downTo index + 1) data[i] = data[i - 1]
        data[index] = value
    }

    /** Swap-remove the element at [index] (O(1); does not preserve order). */
    fun removeAt(index: Int) {
        data[index] = data[--size]
    }

    /** Find the first occurrence of [value] and swap-remove it; returns true if found. */
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

    /** Snapshot the current contents as a `Set<Long>`. Boxes, so not for hot loops. */
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

    /** Sort the live elements in place; [descending] reverses the result. A 64-bit element leaves
     *  no room to pack a key, so there is no counterpart to [IntArrayList.sortByIntKey]. */
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

    /**
     * Index of the first element `>= element` in `[0, size)`, assuming the list is sorted
     * **strictly ascending** (no duplicates) — the lower-bound / insertion point. Returns
     * [size] when every element is strictly below [element]. O(log size) via [binarySearchLong].
     */
    fun lowerBound(element: Long): Int {
        val idx = data.binarySearchLong(element, 0, size)
        return if (idx >= 0) idx else -(idx + 1)
    }

    inline fun forEach(action: (Long) -> Unit) {
        for (i in 0 until size) action(this[i])
    }
}
