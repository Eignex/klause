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
internal class IntIntMap private constructor(
    private val minKey: Int,
    private val keys: IntArray,
    private val values: IntArray,
    private val present: BooleanArray,
    private val absent: Int,
) {
    operator fun get(key: Int): Int = getOrDefault(key, absent)

    fun contains(key: Int): Boolean = getOrDefault(key, MISSING) != MISSING

    private fun getOrDefault(key: Int, fallback: Int): Int {
        if (keys.isEmpty()) {
            val index = key - minKey
            return if (index in values.indices) values[index] else fallback
        }
        val mask = keys.size - 1
        var index = mixIntKey(key) and mask
        while (present[index]) {
            if (keys[index] == key) return values[index]
            index = (index + 1) and mask
        }
        return fallback
    }

    companion object {
        private const val MISSING: Int = Int.MIN_VALUE + 1

        /**
         * Build a map from parallel key/value arrays. When the key range relative to the
         * entry count is dense (≤ [denseThreshold]×), uses an offset IntArray; otherwise
         * falls back to an open-addressing primitive hash table. [absent] is returned by
         * [get] for keys that aren't present.
         */
        fun build(keys: IntArray, values: IntArray, absent: Int = 0, denseThreshold: Int = 4): IntIntMap {
            require(keys.size == values.size) { "keys/values length mismatch" }
            if (keys.isEmpty()) return IntIntMap(0, EmptyIntArray, EmptyIntArray, EmptyBooleanArray, absent)
            var lo = keys[0]
            var hi = keys[0]
            for (k in keys) {
                if (k < lo) lo = k
                if (k > hi) hi = k
            }
            // Widen before subtracting: `hi - lo` in Int overflows for spans ≥ 2^31 (e.g. keys
            // straddling Int.MIN_VALUE / Int.MAX_VALUE), yielding a bogus tiny range and a dense
            // backing sized to garbage.
            val range = hi.toLong() - lo.toLong() + 1
            return if (range <= denseThreshold.toLong() * keys.size) {
                val arr = IntArray(range.toInt()) { absent }
                for (i in keys.indices) arr[keys[i] - lo] = values[i]
                IntIntMap(lo, EmptyIntArray, arr, EmptyBooleanArray, absent)
            } else {
                buildSparse(keys, values, absent)
            }
        }

        private fun buildSparse(keys: IntArray, values: IntArray, absent: Int): IntIntMap {
            val capacity = openAddressingCapacity(keys.size)
            val storedKeys = IntArray(capacity)
            val storedValues = IntArray(capacity)
            val present = BooleanArray(capacity)
            val mask = capacity - 1
            for (i in keys.indices) {
                var index = mixIntKey(keys[i]) and mask
                while (present[index]) {
                    if (storedKeys[index] == keys[i]) break
                    index = (index + 1) and mask
                }
                storedKeys[index] = keys[i]
                storedValues[index] = values[i]
                present[index] = true
            }
            return IntIntMap(0, storedKeys, storedValues, present, absent)
        }
    }
}
