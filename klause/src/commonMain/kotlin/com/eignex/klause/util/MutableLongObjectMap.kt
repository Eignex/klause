package com.eignex.klause.util

/**
 * Mutable open-addressing `Long → V` hash map specialised to keep the **key** unboxed, unlike a stdlib
 * `HashMap<Long, V>` which boxes the key on every `put`/`get`. The long-keyed sibling of
 * [MutableIntObjectMap]: reach for it when the key is a value that may exceed 32-bit range (a
 * pseudo-Boolean weight, a wide cover value) but the value is a reference type.
 *
 * Linear-probed, capacity a power of two, load factor kept ≤ 0.5. Occupancy is tracked by a parallel
 * `used` bitmap rather than a sentinel key, so **any** `Long` key is storable. Removal uses
 * backward-shift deletion (Knuth 6.4 algorithm R), so the table holds no tombstones.
 *
 * Not thread-safe. Iteration order is unspecified (hash order); callers needing a deterministic order
 * must sort the keys themselves.
 */
internal class MutableLongObjectMap<V>(initialCapacity: Int = 8) {
    private var keys: LongArray
    private var values: Array<Any?>
    private var used: BooleanArray
    private var mask: Int

    /** Number of entries currently stored. */
    var size: Int = 0
        private set

    init {
        val cap = openAddressingCapacity(initialCapacity)
        keys = LongArray(cap)
        values = arrayOfNulls(cap)
        used = BooleanArray(cap)
        mask = cap - 1
    }

    /** Value for [key], or `null` if absent. */
    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Long): V? {
        var i = mixLongKey(key) and mask
        while (used[i]) {
            if (keys[i] == key) return values[i] as V
            i = (i + 1) and mask
        }
        return null
    }

    /** Value for [key], throwing if absent. The unboxed-key analogue of `Map.getValue`. */
    fun getValue(key: Long): V = get(key) ?: throw NoSuchElementException("key $key is missing in the map")

    fun containsKey(key: Long): Boolean {
        var i = mixLongKey(key) and mask
        while (used[i]) {
            if (keys[i] == key) return true
            i = (i + 1) and mask
        }
        return false
    }

    /** Insert or overwrite [key] → [value]. */
    fun put(key: Long, value: V) {
        var i = mixLongKey(key) and mask
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
    inline fun getOrPut(key: Long, default: () -> V): V {
        val existing = get(key)
        if (existing != null) return existing
        val created = default()
        put(key, created)
        return created
    }

    /** Remove [key]; returns true if it was present. Backward-shift keeps the table tombstone-free. */
    fun remove(key: Long): Boolean {
        var i = mixLongKey(key) and mask
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

    fun clear() {
        used.fill(false)
        values.fill(null)
        size = 0
    }

    /** Invoke [action] for each (key, value) entry. Iteration order is unspecified. */
    @Suppress("UNCHECKED_CAST")
    inline fun forEach(action: (key: Long, value: V) -> Unit) {
        val k = keysInternal
        val v = valuesInternal
        val u = usedInternal
        for (i in u.indices) if (u[i]) action(k[i], v[i] as V)
    }

    @PublishedApi internal val keysInternal: LongArray get() = keys

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
            val home = mixLongKey(keys[j]) and mask
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
        keys = LongArray(newCap)
        values = arrayOfNulls(newCap)
        used = BooleanArray(newCap)
        mask = newCap - 1
        for (i in oldUsed.indices) {
            if (!oldUsed[i]) continue
            var j = mixLongKey(oldKeys[i]) and mask
            while (used[j]) j = (j + 1) and mask
            used[j] = true
            keys[j] = oldKeys[i]
            values[j] = oldValues[i]
        }
    }
}
