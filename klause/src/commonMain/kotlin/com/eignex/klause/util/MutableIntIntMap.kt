package com.eignex.klause.util

/**
 * Mutable open-addressing `Int → Int` hash map specialised to avoid the key/value autoboxing
 * a stdlib `HashMap<Int, Int>` pays on every `put`/`get`. This is the mutable sibling of the
 * build-once read-only [IntIntMap]: use [IntIntMap] for a fixed lookup table built from parallel
 * arrays, and this when entries are inserted/incremented/removed on a hot path — count maps in
 * `propagate()`, per-move delta accumulators in local search, etc.
 *
 * Linear-probed, capacity a power of two, load factor kept ≤ 0.5 so probe chains stay short.
 * Occupancy is tracked by a parallel `used` bitmap rather than a sentinel empty key, so **any**
 * `Int` key (including [Int.MIN_VALUE] and `0`) is storable. Removal uses backward-shift deletion
 * (Knuth 6.4 algorithm R), so the table holds no tombstones and stays compact across reuse —
 * making [clear] + refill cheap enough to reuse one instance per factor instead of reallocating.
 *
 * Not thread-safe. Iteration order is unspecified (hash order); callers needing a deterministic
 * order must sort the keys themselves.
 */
internal class MutableIntIntMap(initialCapacity: Int = 8) {
    private var keys: IntArray
    private var values: IntArray
    private var used: BooleanArray
    private var mask: Int

    /** Number of entries currently stored. */
    var size: Int = 0
        private set

    init {
        var cap = 8
        while (cap < initialCapacity * 2) cap *= 2
        keys = IntArray(cap)
        values = IntArray(cap)
        used = BooleanArray(cap)
        mask = cap - 1
    }

    /** Value for [key], or [default] if absent. */
    fun getOrDefault(key: Int, default: Int): Int {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return default
    }

    fun containsKey(key: Int): Boolean {
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return true
            i = (i + 1) and mask
        }
        return false
    }

    /** Insert or overwrite [key] → [value]. */
    fun put(key: Int, value: Int) {
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
    fun addTo(key: Int, delta: Int): Int {
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

    /** Remove [key]; returns true if it was present. Backward-shift keeps the table tombstone-free. */
    fun remove(key: Int): Boolean {
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
        // Only the occupancy bitmap needs resetting; stale key/value entries are unreachable
        // while their slot is marked free.
        used.fill(false)
        size = 0
    }

    /** Invoke [action] for each (key, value) entry. Iteration order is unspecified. */
    inline fun forEach(action: (key: Int, value: Int) -> Unit) {
        val k = keysInternal
        val v = valuesInternal
        val u = usedInternal
        for (i in u.indices) if (u[i]) action(k[i], v[i])
    }

    // Exposed for the inline [forEach]; not part of the public contract.
    @PublishedApi internal val keysInternal: IntArray get() = keys

    @PublishedApi internal val valuesInternal: IntArray get() = values

    @PublishedApi internal val usedInternal: BooleanArray get() = used

    /** Backward-shift deletion (Knuth 6.4 algorithm R): after freeing slot [start], pull any
     *  later probe-chain entry forward when its home slot lies outside the cyclic gap `(i, j]`,
     *  so the chain stays contiguous and no tombstone is needed. */
    private fun deleteSlot(start: Int) {
        var i = start
        used[i] = false
        var j = i
        while (true) {
            j = (j + 1) and mask
            if (!used[j]) return
            val home = mix(keys[j]) and mask
            // If `home` is cyclically within (i, j], entry j is still reachable past the hole
            // and must not move; otherwise it would be stranded, so shift it into the hole.
            val mustStay = if (i <= j) home > i && home <= j else home > i || home <= j
            if (mustStay) continue
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

    /** Fibonacci-multiplicative hash; good distribution for sequential int keys. */
    private fun mix(x: Int): Int = (x * -0x61c88647).ushr(0)
}
