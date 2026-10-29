package com.eignex.klause.util

/**
 * Primitive open-addressing Int set with linear probing, power-of-two capacity and
 * load factor at most 0.5. A separate occupancy bitmap permits every Int value.
 * Backward-shift deletion avoids tombstones. Empty sets share an immutable one-slot
 * table; first insertion allocates private storage. Not thread-safe; iteration
 * order is unspecified.
 */
internal class IntHashSet(initialCapacity: Int = 8) {
    private var keys: IntArray = EMPTY_KEYS
    private var used: BooleanArray = EMPTY_USED
    private var mask: Int = 0

    /** Backing size to allocate on the first [add] — next power of two keeping load ≤ 0.5. */
    private val initialCap: Int = openAddressingCapacity(initialCapacity)

    internal val allocatedBytes: Long
        get() = if (used === EMPTY_USED) 0L else keys.size.toLong() * (Int.SIZE_BYTES + 1L)

    /** Number of members currently in the set. */
    var size: Int = 0
        private set

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

    /** Remove [value]; returns true if it was present. Never writes through the shared empty table. */
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
        if (used !== EMPTY_USED) used.fill(false)
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

    private fun allocateBacking() {
        keys = IntArray(initialCap)
        used = BooleanArray(initialCap)
        mask = initialCap - 1
    }

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

    private fun mix(x: Int): Int = mixIntKey(x)

    private companion object {
        // A permanently unoccupied single slot shared by every set until its first add, so sets
        // that stay empty allocate nothing and reads probe it like any table (mask 0 → slot 0).
        val EMPTY_KEYS = IntArray(1)
        val EMPTY_USED = BooleanArray(1)
    }
}
