package com.eignex.klause.util

/**
 * Read-only int→long lookup table backed by a flat [LongArray] indexed by `key - minKey`.
 * Keys outside `[minKey, minKey + values.size)` resolve to [absent]; callers that need a
 * "is key present" distinction beyond the sentinel can build the map with a sentinel
 * value the data never legitimately holds (`-1` for non-negative payloads, `Long.MIN_VALUE`
 * otherwise).
 *
 * The int-value twin [IntIntMap], with the same dense/sparse backing choice — use this when the
 * payload is a value that may exceed 32-bit range (a signed pseudo-Boolean weight). Keys are
 * variable ids, dense by construction in klause's allocator.
 */
internal class IntLongMap private constructor(private val backing: Backing, private val absent: Long) {
    operator fun get(key: Int): Long = backing.get(key, absent)

    fun contains(key: Int): Boolean = backing.get(key, MISSING) != MISSING

    companion object {
        private const val MISSING: Long = Long.MIN_VALUE + 1

        /**
         * Build a map from parallel key/value arrays. When the key range relative to the
         * entry count is dense (≤ [denseThreshold]×), uses an offset LongArray; otherwise
         * falls back to an open-addressing primitive hash table. [absent] is returned by
         * [get] for keys that aren't present.
         */
        fun build(keys: IntArray, values: LongArray, absent: Long = 0, denseThreshold: Int = 4): IntLongMap {
            require(keys.size == values.size) { "keys/values length mismatch" }
            if (keys.isEmpty()) return IntLongMap(EmptyBacking, absent)
            var lo = keys[0]
            var hi = keys[0]
            for (k in keys) {
                if (k < lo) lo = k
                if (k > hi) hi = k
            }
            // Widen before subtracting: `hi - lo` in Int overflows for spans ≥ 2^31 (keys straddling
            // Int.MIN_VALUE / Int.MAX_VALUE), yielding a bogus tiny range and a dense backing sized to garbage.
            val range = hi.toLong() - lo.toLong() + 1
            return if (range <= denseThreshold.toLong() * keys.size) {
                val arr = LongArray(range.toInt()) { absent }
                for (i in keys.indices) arr[keys[i] - lo] = values[i]
                IntLongMap(ArrayBacking(lo, arr), absent)
            } else {
                IntLongMap(HashBacking.build(keys, values), absent)
            }
        }
    }

    private sealed interface Backing {
        fun get(key: Int, absent: Long): Long
    }

    private data object EmptyBacking : Backing {
        override fun get(key: Int, absent: Long): Long = absent
    }

    private class ArrayBacking(val minKey: Int, val values: LongArray) : Backing {
        override fun get(key: Int, absent: Long): Long {
            val idx = key - minKey
            return if (idx in values.indices) values[idx] else absent
        }
    }

    /**
     * Open-addressing linear-probe hash table specialised for `Int → Long`, the sparse-key fallback
     * for [IntLongMap.build]. Capacity is a power of two with load factor ≤ 0.5 so probe chains stay
     * short; the parallel `present` bit distinguishes an empty slot from an entry whose value equals
     * the [absent] sentinel.
     */
    private class HashBacking private constructor(
        private val keys: IntArray,
        private val values: LongArray,
        private val present: BooleanArray,
    ) : Backing {
        override fun get(key: Int, absent: Long): Long {
            val mask = keys.size - 1
            var i = mixIntKey(key) and mask
            while (present[i]) {
                if (keys[i] == key) return values[i]
                i = (i + 1) and mask
            }
            return absent
        }

        companion object {
            fun build(keys: IntArray, values: LongArray): HashBacking {
                val cap = openAddressingCapacity(keys.size)
                val k = IntArray(cap)
                val v = LongArray(cap)
                val p = BooleanArray(cap)
                val mask = cap - 1
                for (i in keys.indices) {
                    var idx = mixIntKey(keys[i]) and mask
                    while (p[idx]) {
                        if (k[idx] == keys[i]) break
                        idx = (idx + 1) and mask
                    }
                    k[idx] = keys[i]
                    v[idx] = values[i]
                    p[idx] = true
                }
                return HashBacking(k, v, p)
            }
        }
    }
}
