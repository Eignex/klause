package com.eignex.klause.util

/**
 * Primitive-key Long-to-Int map with linear probing, power-of-two capacity and load
 * factor at most 0.5. A separate occupancy bitmap permits every key value;
 * backward-shift deletion avoids tombstones. See [MutableIntIntMap] for related lookup
 * storage. Not thread-safe; iteration order is unspecified.
 */
internal class MutableLongIntMap(initialCapacity: Int = 8) {
    private var keys: LongArray
    private var values: IntArray
    private var used: BooleanArray
    private var mask: Int

    internal val primitiveCapacityBytes: Long get() = keys.size.toLong() * 13L

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

    /** Remove [key] and return its prior value, or [default] if absent, in one table walk. */
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

    fun removeAndUpdate(
        key: Long,
        updateKey: Long?,
        updateValue: Int,
        fund: (Long) -> Unit,
        ready: () -> Unit,
    ): Boolean {
        require(key != updateKey)
        val removed = fundedSlot(key, fund)
        val updated = updateKey?.let { fundedSlot(it, fund) } ?: -1
        if (updateKey != null && updated < 0) return false
        val destination = if (removed >= 0) removalDestination(removed, updated, fund) else updated
        fund(4L)
        ready()
        if (removed >= 0) {
            deleteSlot(removed)
            size--
        }
        if (updateKey != null) values[destination] = updateValue
        return true
    }

    private fun fundedSlot(key: Long, fund: (Long) -> Unit): Int {
        fund(1L)
        var index = mix(key) and mask
        while (true) {
            fund(4L)
            if (!used[index]) return -1
            if (keys[index] == key) return index
            index = (index + 1) and mask
        }
    }

    private fun removalDestination(start: Int, updated: Int, fund: (Long) -> Unit): Int {
        var hole = start
        var cursor = start
        var destination = updated
        while (true) {
            // Fund the probe and its matching backward-shift publication before either mapping changes.
            fund(16L)
            cursor = (cursor + 1) and mask
            if (!used[cursor]) return destination
            val home = mix(keys[cursor]) and mask
            if (!mustStayDuringShift(home, hole, cursor)) {
                if (cursor == updated) destination = hole
                hole = cursor
            }
        }
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

    /** Backward-shift deletion; see [mustStayDuringShift]. */
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
