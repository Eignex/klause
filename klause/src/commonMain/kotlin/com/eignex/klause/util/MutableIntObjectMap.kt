package com.eignex.klause.util

/**
 * Primitive-key Int-to-object map with linear probing, power-of-two capacity and load
 * factor at most 0.5. A separate occupancy bitmap permits every key value;
 * backward-shift deletion avoids tombstones. See [MutableIntIntMap] for related lookup
 * storage. Not thread-safe; iteration order is unspecified.
 */
internal class MutableIntObjectMap<V>(initialCapacity: Int = 8) {
    private var keys: IntArray
    private var values: Array<Any?>
    private var used: BooleanArray
    private var mask: Int

    internal val primitiveCapacityBytes: Long get() = keys.size.toLong() * 5L

    internal val referenceCapacity: Int get() = values.size

    /** Number of entries currently stored. */
    var size: Int = 0
        private set

    init {
        val cap = openAddressingCapacity(initialCapacity)
        keys = IntArray(cap)
        values = arrayOfNulls(cap)
        used = BooleanArray(cap)
        mask = cap - 1
    }

    /** Value for [key], or `null` if absent. */
    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Int): V? {
        var i = mixIntKey(key) and mask
        while (used[i]) {
            if (keys[i] == key) return values[i] as V
            i = (i + 1) and mask
        }
        return null
    }

    /** Value for [key], throwing if absent. The unboxed-key analogue of `Map.getValue`. */
    fun getValue(key: Int): V = get(key) ?: throw NoSuchElementException("key $key is missing in the map")

    fun containsKey(key: Int): Boolean {
        var i = mixIntKey(key) and mask
        while (used[i]) {
            if (keys[i] == key) return true
            i = (i + 1) and mask
        }
        return false
    }

    /** Insert or overwrite [key] → [value]. */
    fun put(key: Int, value: V) {
        var i = mixIntKey(key) and mask
        while (used[i]) {
            if (keys[i] == key) {
                values[i] = value
                return
            }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = key
        values[i] = value
        size++
        if (size * 2 > keys.size) grow()
    }

    /** Value for [key], inserting and returning [default]`()` if absent. */
    @Suppress("UNCHECKED_CAST")
    inline fun getOrPut(key: Int, default: () -> V): V {
        val existing = get(key)
        if (existing != null) return existing
        val created = default()
        put(key, created)
        return created
    }

    /** Remove [key]; returns true if it was present. Backward-shift keeps the table tombstone-free. */
    fun remove(key: Int): Boolean {
        var i = mixIntKey(key) and mask
        while (used[i]) {
            if (keys[i] == key) {
                deleteSlot(i)
                size--
                return true
            }
            i = (i + 1) and mask
        }
        return false
    }

    fun isEmpty(): Boolean = size == 0

    /**
     * Remove every entry whose key exceeds [threshold]. Keys are collected before any removal, since
     * backward-shift deletion moves entries that an in-place iteration would then skip.
     */
    fun removeKeysAbove(threshold: Int) {
        val doomed = IntArrayList()
        forEach { key, _ -> if (key > threshold) doomed.add(key) }
        doomed.forEach { remove(it) }
    }

    /** Value stored under the greatest key, or null when the map is empty. */
    fun valueAtMaxKey(): V? {
        var bestKey = Int.MIN_VALUE
        var best: V? = null
        forEach { key, value ->
            if (best == null || key >= bestKey) {
                bestKey = key
                best = value
            }
        }
        return best
    }

    fun clear() {
        used.fill(false)
        values.fill(null)
        size = 0
    }

    /** Invoke [action] for each (key, value) entry. Iteration order is unspecified. */
    @Suppress("UNCHECKED_CAST")
    inline fun forEach(action: (key: Int, value: V) -> Unit) {
        val k = keysInternal
        val v = valuesInternal
        val u = usedInternal
        for (i in u.indices) if (u[i]) action(k[i], v[i] as V)
    }

    @PublishedApi internal val keysInternal: IntArray get() = keys

    @PublishedApi internal val valuesInternal: Array<Any?> get() = values

    @PublishedApi internal val usedInternal: BooleanArray get() = used

    private fun deleteSlot(start: Int) {
        var i = start
        used[i] = false
        values[i] = null
        var j = i
        while (true) {
            j = (j + 1) and mask
            if (!used[j]) return
            val home = mixIntKey(keys[j]) and mask
            if (mustStayDuringShift(home, i, j)) continue
            keys[i] = keys[j]
            values[i] = values[j]
            used[i] = true
            i = j
            used[i] = false
            values[i] = null
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val oldUsed = used
        val newCap = keys.size * 2
        keys = IntArray(newCap)
        values = arrayOfNulls(newCap)
        used = BooleanArray(newCap)
        mask = newCap - 1
        for (i in oldUsed.indices) {
            if (!oldUsed[i]) continue
            var j = mixIntKey(oldKeys[i]) and mask
            while (used[j]) j = (j + 1) and mask
            used[j] = true
            keys[j] = oldKeys[i]
            values[j] = oldValues[i]
        }
    }
}
