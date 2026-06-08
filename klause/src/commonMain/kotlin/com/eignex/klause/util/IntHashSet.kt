package com.eignex.klause.util

/**
 * Mutable open-addressing `Int` set specialised to avoid the per-element autoboxing a stdlib
 * `HashSet<Int>` / `LinkedHashSet<Int>` pays. Use this on hot paths where the members are
 * **arbitrary** ints — domain values, signed literals, value classes — rather than dense ids
 * in `[0, capacity)` (for which [IntSwapSet] is cheaper, being array-backed with O(1) sampling).
 *
 * Linear-probed, capacity a power of two, load factor kept ≤ 0.5. Occupancy is tracked by a
 * parallel `used` bitmap rather than a sentinel, so any `Int` (including [Int.MIN_VALUE] and `0`)
 * is a valid member. [remove] uses backward-shift deletion so the table is tombstone-free and
 * reusable via [clear].
 *
 * Not thread-safe. Iteration order is unspecified; callers needing determinism must sort.
 */
internal class IntHashSet(initialCapacity: Int = 8) {
    private var keys: IntArray
    private var used: BooleanArray
    private var mask: Int

    /** Number of members currently in the set. */
    var size: Int = 0
        private set

    init {
        var cap = 8
        while (cap < initialCapacity * 2) cap *= 2
        keys = IntArray(cap)
        used = BooleanArray(cap)
        mask = cap - 1
    }

    operator fun contains(value: Int): Boolean {
        var i = mix(value) and mask
        while (used[i]) {
            if (keys[i] == value) return true
            i = (i + 1) and mask
        }
        return false
    }

    /** Add [value]; returns true if it was newly inserted, false if already present. */
    fun add(value: Int): Boolean {
        var i = mix(value) and mask
        while (used[i]) {
            if (keys[i] == value) return false
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = value
        size++
        if (size * 2 > keys.size) grow()
        return true
    }

    /** Remove [value]; returns true if it was present. */
    fun remove(value: Int): Boolean {
        var i = mix(value) and mask
        while (used[i]) {
            if (keys[i] == value) {
                deleteSlot(i)
                size--
                return true
            }
            i = (i + 1) and mask
        }
        return false
    }

    fun isEmpty(): Boolean = size == 0

    /** Snapshot the members into a fresh [IntArray] (unspecified order). */
    fun toIntArray(): IntArray {
        val out = IntArray(size)
        var w = 0
        val k = keys
        val u = used
        for (i in u.indices) if (u[i]) out[w++] = k[i]
        return out
    }

    fun clear() {
        used.fill(false)
        size = 0
    }

    /** Invoke [action] for each member. Iteration order is unspecified. */
    inline fun forEach(action: (Int) -> Unit) {
        val k = keysInternal
        val u = usedInternal
        for (i in u.indices) if (u[i]) action(k[i])
    }

    @PublishedApi internal val keysInternal: IntArray get() = keys

    @PublishedApi internal val usedInternal: BooleanArray get() = used

    /** Backward-shift deletion — see [MutableIntIntMap.deleteSlot] for the algorithm. */
    private fun deleteSlot(start: Int) {
        var i = start
        used[i] = false
        var j = i
        while (true) {
            j = (j + 1) and mask
            if (!used[j]) return
            val home = mix(keys[j]) and mask
            val mustStay = if (i <= j) home > i && home <= j else home > i || home <= j
            if (mustStay) continue
            keys[i] = keys[j]
            used[i] = true
            i = j
            used[i] = false
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldUsed = used
        val newCap = keys.size * 2
        keys = IntArray(newCap)
        used = BooleanArray(newCap)
        mask = newCap - 1
        for (i in oldUsed.indices) {
            if (!oldUsed[i]) continue
            var j = mix(oldKeys[i]) and mask
            while (used[j]) j = (j + 1) and mask
            used[j] = true
            keys[j] = oldKeys[i]
        }
    }

    /** Fibonacci-multiplicative hash; good distribution for sequential int keys. */
    private fun mix(x: Int): Int = (x * -0x61c88647).ushr(0)
}
