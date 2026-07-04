package com.eignex.klause.util

/**
 * Mutable open-addressing `Long → Int` hash map — the `Long`-keyed analogue of [MutableIntIntMap],
 * eliminating the key/value autoboxing a stdlib `HashMap<Long, Int>` pays. Used for engine hot-path
 * tables keyed by a packed `Long` (a `(factorId, literal)` or `(intVar, kind, threshold)` tuple),
 * e.g. `PropagationState.boolWatchPos` (queried/mutated on every two-watched-literal watcher move
 * during BCP) and the bound-atom reverse index.
 *
 * Linear-probed, capacity a power of two, load factor kept ≤ 0.5. Occupancy is tracked by a parallel
 * `used` bitmap rather than a sentinel, so any `Long` key (including `0L` and [Long.MIN_VALUE]) is
 * storable. [remove] uses backward-shift deletion (Knuth 6.4 algorithm R), so the table holds no
 * tombstones and stays compact across reuse.
 *
 * Not thread-safe. Iteration order is unspecified.
 */
internal class MutableLongIntMap(initialCapacity: Int = 8) {
    private var keys: LongArray
    private var values: IntArray
    private var used: BooleanArray
    private var mask: Int

    /** Number of entries currently stored. */
    var size: Int = 0
        private set

    init {
        val cap = openAddressingCapacity(initialCapacity)
        keys = LongArray(cap)
        values = IntArray(cap)
        used = BooleanArray(cap)
        mask = cap - 1
    }

    /** Value for [key], or [default] if absent. */
    fun getOrDefault(key: Long, default: Int): Int {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return default
    }

    fun containsKey(key: Long): Boolean {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return true
            i = (i + 1) and mask
        }
        return false
    }

    /** Insert or overwrite [key] → [value]. */
    fun put(key: Long, value: Int) {
        var i = mix(key) and mask
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

    /**
     * Add [delta] to the value at [key] (treating an absent key as `0`) and return the new value.
     * The count-map idiom `m[k] = (m[k] ?: 0) + delta` in one probe instead of two.
     */
    fun addTo(key: Long, delta: Int): Int {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) {
                val nv = values[i] + delta
                values[i] = nv
                return nv
            }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = key
        values[i] = delta
        size++
        if (size * 2 > keys.size) grow()
        return delta
    }

    /** Remove [key] and return its prior value, or [default] if absent. One table walk — for
     *  hot callers that would otherwise pair a [getOrDefault] with a [remove] on the same key. */
    fun removeAndGet(key: Long, default: Int): Int {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) {
                val v = values[i]
                deleteSlot(i)
                size--
                return v
            }
            i = (i + 1) and mask
        }
        return default
    }

    /** Remove [key]; returns true if it was present. Backward-shift keeps the table tombstone-free. */
    fun remove(key: Long): Boolean {
        var i = mix(key) and mask
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
        size = 0
    }

    /** Invoke [action] for each (key, value) entry. Iteration order is unspecified. */
    inline fun forEach(action: (key: Long, value: Int) -> Unit) {
        val k = keysInternal
        val v = valuesInternal
        val u = usedInternal
        for (i in u.indices) if (u[i]) action(k[i], v[i])
    }

    @PublishedApi internal val keysInternal: LongArray get() = keys

    @PublishedApi internal val valuesInternal: IntArray get() = values

    @PublishedApi internal val usedInternal: BooleanArray get() = used

    /** Backward-shift deletion (Knuth 6.4 algorithm R) — identical to [MutableIntIntMap.deleteSlot]. */
    private fun deleteSlot(start: Int) {
        var i = start
        used[i] = false
        var j = i
        while (true) {
            j = (j + 1) and mask
            if (!used[j]) return
            val home = mix(keys[j]) and mask
            if (mustStayDuringShift(home, i, j)) continue
            keys[i] = keys[j]
            values[i] = values[j]
            used[i] = true
            i = j
            used[i] = false
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val oldUsed = used
        val newCap = keys.size * 2
        keys = LongArray(newCap)
        values = IntArray(newCap)
        used = BooleanArray(newCap)
        mask = newCap - 1
        for (i in oldUsed.indices) {
            if (!oldUsed[i]) continue
            var j = mix(oldKeys[i]) and mask
            while (used[j]) j = (j + 1) and mask
            used[j] = true
            keys[j] = oldKeys[i]
            values[j] = oldValues[i]
        }
    }

    private fun mix(x: Long): Int = mixLongKey(x)
}
