package com.eignex.klause.util

/**
 * Primitive open-addressing Long set with linear probing and load factor at most 0.5.
 * A separate occupancy bitmap permits every Long value; backward-shift deletion
 * avoids tombstones. Empty sets share an immutable one-slot table until insertion.
 * Not thread-safe; iteration order is unspecified.
 */
internal class LongHashSet(initialCapacity: Int = 8) {
    private var keys: LongArray = EMPTY_KEYS
    private var used: BooleanArray = EMPTY_USED
    private var mask: Int = 0

    /** Backing size to allocate on the first [add] — next power of two keeping load ≤ 0.5. */
    private val initialCap: Int = openAddressingCapacity(initialCapacity)

    /** Number of members currently in the set. */
    var size: Int = 0
        private set

    operator fun contains(value: Long): Boolean {
        var i = mix(value) and mask
        while (used[i]) {
            if (keys[i] == value) return true
            i = (i + 1) and mask
        }
        return false
    }

    /** Add [value]; returns true if it was newly inserted, false if already present. */
    fun add(value: Long): Boolean {
        if (used === EMPTY_USED) allocateBacking()
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

    /** Remove [value]; returns true if it was present. (On the shared empty table slot 0 reads
     *  as unoccupied, so this returns false without ever writing through the shared backing.) */
    fun remove(value: Long): Boolean {
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

    /** Snapshot the members into a fresh [LongArray] (unspecified order). */
    fun toLongArray(): LongArray {
        val out = LongArray(size)
        var w = 0
        val k = keys
        val u = used
        for (i in u.indices) if (u[i]) out[w++] = k[i]
        return out
    }

    fun clear() {
        if (used !== EMPTY_USED) used.fill(false) // never write through the shared empty table
        size = 0
    }

    /** Invoke [action] for each member. Iteration order is unspecified. */
    inline fun forEach(action: (Long) -> Unit) {
        val k = keysInternal
        val u = usedInternal
        for (i in u.indices) if (u[i]) action(k[i])
    }

    @PublishedApi internal val keysInternal: LongArray get() = keys

    @PublishedApi internal val usedInternal: BooleanArray get() = used

    private fun allocateBacking() {
        keys = LongArray(initialCap)
        used = BooleanArray(initialCap)
        mask = initialCap - 1
    }

    /** Backward-shift deletion — see [MutableIntIntMap.deleteSlot] for the algorithm. */
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
            used[i] = true
            i = j
            used[i] = false
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldUsed = used
        val newCap = keys.size * 2
        keys = LongArray(newCap)
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

    private fun mix(x: Long): Int = mixLongKey(x)

    private companion object {
        // Shared immutable empty table: a single slot that is permanently unoccupied. Every fresh
        // set aliases it until its first add, so empty-lifetime sets allocate nothing and the read
        // paths probe it like any table (mask 0 → slot 0 → unoccupied). Never written through.
        val EMPTY_KEYS = LongArray(1)
        val EMPTY_USED = BooleanArray(1)
    }
}
