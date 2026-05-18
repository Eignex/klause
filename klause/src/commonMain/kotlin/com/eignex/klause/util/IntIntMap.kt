package com.eignex.klause.util

/**
 * Read-only int→int lookup table backed by a flat [IntArray] indexed by `key - minKey`.
 * Keys outside `[minKey, minKey + values.size)` resolve to [absent]; callers that need a
 * "is key present" distinction beyond the sentinel can build the map with a sentinel
 * value the data never legitimately holds (`-1` for non-negative payloads, `Int.MIN_VALUE`
 * otherwise).
 *
 * Compared to a `Map<Int, Int>` from the stdlib this avoids autoboxing both keys and
 * values; the cost is one IntArray allocation sized to the key range. For sparse keys
 * (large range, few entries) it can waste memory — use [build] which transparently
 * falls back to a per-key linear-probed table in that case.
 *
 * Use this for per-factor caches keyed by variable id, since var ids are dense by
 * construction in klause's allocator. Examples: `Cardinality.signedOccurrencesByVar`,
 * `AllDifferent.occurrencesByVar`, `Clause.litIndexByVar`.
 */
class IntIntMap private constructor(
    private val backing: Backing,
    private val absent: Int,
) {
    operator fun get(key: Int): Int = backing.get(key, absent)

    fun contains(key: Int): Boolean = backing.get(key, MISSING) != MISSING

    companion object {
        private const val MISSING: Int = Int.MIN_VALUE + 1

        /**
         * Build a map from parallel key/value arrays. When the key range relative to the
         * entry count is dense (≤ [denseThreshold]×), uses an offset IntArray; otherwise
         * falls back to an open-addressing primitive hash table. [absent] is returned by
         * [get] for keys that aren't present.
         */
        fun build(
            keys: IntArray,
            values: IntArray,
            absent: Int = 0,
            denseThreshold: Int = 4,
        ): IntIntMap {
            require(keys.size == values.size) { "keys/values length mismatch" }
            if (keys.isEmpty()) return IntIntMap(EmptyBacking, absent)
            var lo = keys[0]; var hi = keys[0]
            for (k in keys) { if (k < lo) lo = k; if (k > hi) hi = k }
            val range = (hi - lo).toLong() + 1
            return if (range <= denseThreshold.toLong() * keys.size) {
                val arr = IntArray(range.toInt()) { absent }
                for (i in keys.indices) arr[keys[i] - lo] = values[i]
                IntIntMap(ArrayBacking(lo, arr), absent)
            } else {
                IntIntMap(HashBacking.build(keys, values), absent)
            }
        }
    }

    private sealed interface Backing {
        fun get(key: Int, absent: Int): Int
    }

    private data object EmptyBacking : Backing {
        override fun get(key: Int, absent: Int): Int = absent
    }

    private class ArrayBacking(val minKey: Int, val values: IntArray) : Backing {
        override fun get(key: Int, absent: Int): Int {
            val idx = key - minKey
            return if (idx in values.indices) values[idx] else absent
        }
    }

    /**
     * Open-addressing linear-probe hash table specialised for `Int → Int`. Used as the
     * sparse-key fallback for [IntIntMap.build]. Capacity is a power of two; we keep load
     * factor ≤ 0.5 so probe chains stay short. The [absent] sentinel marks empty slots;
     * if a real entry's value equals [absent] we still find it via the parallel
     * [presentMask] bit per slot.
     */
    private class HashBacking private constructor(
        private val keys: IntArray,
        private val values: IntArray,
        private val present: BooleanArray,
    ) : Backing {
        override fun get(key: Int, absent: Int): Int {
            val mask = keys.size - 1
            var i = mix(key) and mask
            while (present[i]) {
                if (keys[i] == key) return values[i]
                i = (i + 1) and mask
            }
            return absent
        }

        companion object {
            fun build(keys: IntArray, values: IntArray): HashBacking {
                // Capacity = next power of two such that load factor ≤ 0.5.
                var cap = 8
                while (cap < keys.size * 2) cap *= 2
                val k = IntArray(cap)
                val v = IntArray(cap)
                val p = BooleanArray(cap)
                val mask = cap - 1
                for (i in keys.indices) {
                    var idx = mix(keys[i]) and mask
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

            /** Fibonacci-multiplicative hash; good distribution for sequential int keys. */
            private fun mix(x: Int): Int = (x * -0x61c88647).ushr(0)
        }
    }
}
