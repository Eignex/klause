package com.eignex.klause.util

/**
 * Mutable open-addressing `Int → Double` hash map specialised to avoid the key/value autoboxing
 * a stdlib `HashMap<Int, Double>` pays on every `put`/`get`. The `Double`-valued sibling of
 * [MutableIntIntMap] — for the LP / objective-weight accumulators keyed by column or variable id.
 *
 * Linear-probed, capacity a power of two, load factor kept ≤ 0.5. Occupancy is tracked by a parallel
 * `used` bitmap rather than a sentinel key, so **any** `Int` key is storable. Removal uses
 * backward-shift deletion (Knuth 6.4 algorithm R), so the table holds no tombstones.
 *
 * Not thread-safe. Iteration order is unspecified (hash order).
 */
internal class MutableIntDoubleMap(initialCapacity: Int = 8) {
    private var keys: IntArray
    private var values: DoubleArray
    private var used: BooleanArray
    private var mask: Int

    /** Number of entries currently stored. */
    var size: Int = 0
        private set

    init {
        val cap = openAddressingCapacity(initialCapacity)
        keys = IntArray(cap)
        values = DoubleArray(cap)
        used = BooleanArray(cap)
        mask = cap - 1
    }

    /** Value for [key], or [default] if absent. */
    fun getOrDefault(key: Int, default: Double): Double {
        var i = mixIntKey(key) and mask
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return default
    }

    fun containsKey(key: Int): Boolean {
        var i = mixIntKey(key) and mask
        while (used[i]) {
            if (keys[i] == key) return true
            i = (i + 1) and mask
        }
        return false
    }

    /** Insert or overwrite [key] → [value]. */
    fun put(key: Int, value: Double) {
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

    /** Add [delta] to the value at [key] (treating an absent key as `0.0`) and return the new value. */
    fun addTo(key: Int, delta: Double): Double {
        var i = mixIntKey(key) and mask
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

    fun clear() {
        used.fill(false)
        size = 0
    }

    /** Invoke [action] for each (key, value) entry. Iteration order is unspecified. */
    inline fun forEach(action: (key: Int, value: Double) -> Unit) {
        val k = keysInternal
        val v = valuesInternal
        val u = usedInternal
        for (i in u.indices) if (u[i]) action(k[i], v[i])
    }

    @PublishedApi internal val keysInternal: IntArray get() = keys

    @PublishedApi internal val valuesInternal: DoubleArray get() = values

    @PublishedApi internal val usedInternal: BooleanArray get() = used

    private fun deleteSlot(start: Int) {
        var i = start
        used[i] = false
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
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val oldUsed = used
        val newCap = keys.size * 2
        keys = IntArray(newCap)
        values = DoubleArray(newCap)
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
