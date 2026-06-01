package com.eignex.klause.solver

import com.eignex.klause.util.binarySearchInt

/**
 * Integer-variable domain. Conceptually a finite set of integers; physically one of:
 *
 *  1. Contiguous `[min..max]` — `holes == null && bitset == null`.
 *  2. Range with a sorted hole list — `holes != null && bitset == null`. Used when the
 *     span is too wide for a bitset to be memory-efficient.
 *  3. Bitset over a narrow span — `bitset != null && holes == null`. One bit per value
 *     starting at [bitsetLo], packed into 64-bit words. Used when the original
 *     contiguous span (at the moment of the first interior exclusion) fits within
 *     [BITSET_THRESHOLD] values. Hot-path methods become bit tests instead of binary
 *     searches.
 *
 * **Invariants**
 *  - `min <= max` (no empty domains; the propagation engine treats domain-empty as
 *    conflict).
 *  - `holes` (when present) is sorted ascending with strictly-distinct values, and
 *    every entry lies strictly between [min] and [max] — endpoints are never holes.
 *  - `bitset` (when present) has bit `value - bitsetLo` set exactly for in-domain
 *    values; bits below `min - bitsetLo` and above `max - bitsetLo` are always 0.
 *  - At most one of `holes` / `bitset` is non-null.
 *
 * **Representation choice**
 *  - Constructors return the contiguous form.
 *  - `excludeValue(v)` on an interior `v` of a contiguous domain transitions to the
 *    bitset form when `(max - min + 1) <= BITSET_THRESHOLD`, otherwise to the holes
 *    form. Once the rep is non-contiguous, subsequent operations preserve the rep
 *    family.
 *  - [BITSET_THRESHOLD] = 256. Each bitset costs ≤ 4 longs = 32 bytes vs O(holes) ints
 *    of 4 bytes apiece; the cutoff is where the bitset's fixed overhead beats roughly
 *    8+ holes worth of array, while still keeping membership tests at O(1).
 *
 * **Public API stability**
 *  Callers continue to construct `IntDomain(min, max)` and read `.min` / `.max`
 *  arithmetic-style. All inspection ([contains], [size], [valueAt], [forEach]) and
 *  mutation ([excludeValue], [withMinAtLeast], [withMaxAtMost]) methods dispatch
 *  internally on the rep; external behaviour is unchanged.
 */
class IntDomain private constructor(
    val min: Int,
    val max: Int,
    /** Sorted ascending interior excluded values. `null` when the domain is either
     *  contiguous or stored as a [bitset]. Public for `inline fun forEach` access;
     *  treat as internal API. */
    @PublishedApi internal val holes: IntArray?,
    /** Packed bitset: bit `(value - bitsetLo)` is set iff `value` is in the domain.
     *  `null` when the domain uses the holes representation or is contiguous. */
    @PublishedApi internal val bitset: LongArray?,
    /** Offset for [bitset]: word `w`, bit `b` corresponds to value `bitsetLo + 64*w + b`. */
    @PublishedApi internal val bitsetLo: Int,
) {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
        require(holes == null || bitset == null) {
            "Cannot use both holes and bitset representations simultaneously"
        }
    }

    /** Source-compatible constructor for the contiguous case. */
    constructor(min: Int, max: Int) : this(min, max, null, null, 0)

    /** Number of values in the domain. */
    val size: Int get() {
        val bs = bitset
        if (bs != null) {
            var s = 0
            for (w in bs.indices) s += bs[w].countOneBits()
            return s
        }
        return max - min + 1 - (holes?.size ?: 0)
    }

    /** True iff [value] lies in the domain. */
    operator fun contains(value: Int): Boolean {
        if (value < min || value > max) return false
        val bs = bitset
        if (bs != null) {
            val off = value - bitsetLo
            return bitsetHasBit(bs, off)
        }
        if (holes == null) return true
        return holes.binarySearchInt(value) < 0
    }

    /** Nearest in-domain value to [value]. */
    fun clamp(value: Int): Int = if (value < min) {
        min
    } else if (value > max) {
        max
    } else {
        value
    }

    /**
     * Return a new domain with [value] excluded, or `this` if [value] is not currently
     * present (idempotent on absent values). Throws [IllegalStateException] if removing
     * [value] would empty the domain.
     *
     * When a contiguous domain has an interior value removed, the new domain switches
     * to the bitset form when `(max - min + 1) ≤ BITSET_THRESHOLD`, otherwise to the
     * holes form. Subsequent excludes preserve the rep.
     */
    fun excludeValue(value: Int): IntDomain {
        if (!contains(value)) return this
        val bs = bitset
        if (bs != null) return excludeFromBitset(value, bs)
        return when {
            value == min -> {
                var newMin = min + 1
                if (holes != null) {
                    while (newMin <= max && holes.binarySearchInt(newMin) >= 0) newMin++
                }
                check(newMin <= max) { "Empty domain after excludeValue($value)" }
                val newHoles = trimHolesBelow(holes, newMin + 1)
                IntDomain(newMin, max, newHoles, null, 0)
            }

            value == max -> {
                var newMax = max - 1
                if (holes != null) {
                    while (newMax >= min && holes.binarySearchInt(newMax) >= 0) newMax--
                }
                check(newMax >= min) { "Empty domain after excludeValue($value)" }
                val newHoles = trimHolesAbove(holes, newMax - 1)
                IntDomain(min, newMax, newHoles, null, 0)
            }

            else -> {
                // Interior exclude. Pick the storage based on span when transitioning
                // out of the contiguous representation; otherwise stay in holes.
                if (holes == null && (max - min + 1) <= BITSET_THRESHOLD) {
                    val span = max - min + 1
                    val words = (span + 63) ushr 6
                    val bits = LongArray(words)
                    fillBitsetRange(bits, 0, span)
                    clearBit(bits, value - min)
                    IntDomain(min, max, null, bits, min)
                } else {
                    val newHoles = if (holes == null) {
                        intArrayOf(value)
                    } else {
                        insertSorted(holes, value)
                    }
                    IntDomain(min, max, newHoles, null, 0)
                }
            }
        }
    }

    private fun excludeFromBitset(value: Int, bs: LongArray): IntDomain {
        val newBits = bs.copyOf()
        clearBit(newBits, value - bitsetLo)
        var newMin = min
        var newMax = max
        if (value == min) {
            val firstSet = firstSetBit(newBits)
            check(firstSet >= 0) { "Empty domain after excludeValue($value)" }
            newMin = bitsetLo + firstSet
        }
        if (value == max) {
            val lastSet = lastSetBit(newBits)
            check(lastSet >= 0) { "Empty domain after excludeValue($value)" }
            newMax = bitsetLo + lastSet
        }
        return IntDomain(newMin, newMax, null, newBits, bitsetLo)
    }

    /**
     * Return a domain with min raised to at least [newMin]. Returns `this` when
     * [newMin] is already covered (no-op). Throws [IllegalStateException] on empty.
     */
    fun withMinAtLeast(newMin: Int): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        val bs = bitset
        if (bs != null) {
            val newBits = bs.copyOf()
            clearBitsBelow(newBits, newMin - bitsetLo)
            val firstSet = firstSetBit(newBits)
            check(firstSet >= 0) { "withMinAtLeast($newMin) emptied bitset domain" }
            val m = bitsetLo + firstSet
            check(m <= max) { "withMinAtLeast($newMin): only zero bits remained" }
            return IntDomain(m, max, null, newBits, bitsetLo)
        }
        var m = newMin
        if (holes != null) {
            while (m <= max && holes.binarySearchInt(m) >= 0) m++
        }
        check(m <= max) { "withMinAtLeast($newMin): only holes remained above $newMin" }
        val newHoles = trimHolesBelow(holes, m + 1)
        return IntDomain(m, max, newHoles, null, 0)
    }

    /** Copy of the domain with its max tightened to at most [newMax]. */
    fun withMaxAtMost(newMax: Int): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        val bs = bitset
        if (bs != null) {
            val newBits = bs.copyOf()
            clearBitsAbove(newBits, newMax - bitsetLo)
            val lastSet = lastSetBit(newBits)
            check(lastSet >= 0) { "withMaxAtMost($newMax) emptied bitset domain" }
            val m = bitsetLo + lastSet
            check(m >= min) { "withMaxAtMost($newMax): only zero bits remained" }
            return IntDomain(min, m, null, newBits, bitsetLo)
        }
        var m = newMax
        if (holes != null) {
            while (m >= min && holes.binarySearchInt(m) >= 0) m--
        }
        check(m >= min) { "withMaxAtMost($newMax): only holes remained below $newMax" }
        val newHoles = trimHolesAbove(holes, m - 1)
        return IntDomain(min, m, newHoles, null, 0)
    }

    /**
     * Return the `i`-th value present in the domain (0-indexed in ascending order).
     */
    fun valueAt(i: Int): Int {
        val bs = bitset
        if (bs != null) return bitsetValueAt(bs, i)
        if (holes == null) return min + i
        var v = min + i
        var holeIdx = 0
        while (holeIdx < holes.size && holes[holeIdx] <= v) {
            v++
            holeIdx++
        }
        return v
    }

    private fun bitsetValueAt(bs: LongArray, i: Int): Int {
        var remaining = i
        for (w in bs.indices) {
            val word = bs[w]
            val cnt = word.countOneBits()
            if (remaining < cnt) {
                var temp = word
                var n = remaining
                while (n > 0) {
                    temp = temp and (temp - 1L)
                    n--
                }
                return bitsetLo + (w shl 6) + temp.countTrailingZeroBits()
            }
            remaining -= cnt
        }
        error("valueAt($i) out of range; size=$size")
    }

    /**
     * Iterate every value present in the domain in ascending order. Inlined so callers
     * get the hot-path representation without an indirection.
     */
    inline fun forEach(action: (Int) -> Unit) {
        val bs = bitset
        if (bs != null) {
            for (w in bs.indices) {
                var word = bs[w]
                while (word != 0L) {
                    val lsb = word.countTrailingZeroBits()
                    action(bitsetLo + (w shl 6) + lsb)
                    word = word and (word - 1L)
                }
            }
            return
        }
        val h = holes
        if (h == null) {
            for (v in min..max) action(v)
        } else {
            var holeIdx = 0
            for (v in min..max) {
                if (holeIdx < h.size && h[holeIdx] == v) {
                    holeIdx++
                    continue
                }
                action(v)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is IntDomain) return false
        if (min != other.min || max != other.max) return false
        // Fast path when reps coincide.
        if (bitset != null && other.bitset != null && bitsetLo == other.bitsetLo) {
            return bitset.contentEquals(other.bitset)
        }
        if (holes != null && other.holes != null) {
            return holes.contentEquals(other.holes)
        }
        if (holes == null && other.holes == null && bitset == null && other.bitset == null) {
            return true
        }
        if (size != other.size) return false
        // Mixed reps: fall back to membership comparison.
        var ok = true
        forEach { v -> if (v !in other) ok = false }
        return ok
    }

    override fun hashCode(): Int {
        var h = min * 31 + max
        // Hash from set membership to stay consistent across the contiguous /
        // holes / bitset representations.
        forEach { v -> h = h * 31 + v }
        return h
    }

    override fun toString(): String = when {
        bitset != null -> {
            val sb = StringBuilder()
            sb.append("IntDomain($min..$max bitset[")
            var first = true
            forEach { v ->
                if (!first) sb.append(",")
                sb.append(v)
                first = false
            }
            sb.append("])")
            sb.toString()
        }

        holes != null -> "IntDomain($min..$max - ${holes.toList()})"

        else -> "IntDomain($min..$max)"
    }

    /** Factory helpers for [IntDomain]. */
    companion object {
        /** Span threshold (inclusive) below which interior exclusions on a contiguous
         *  domain transition to bitset storage instead of holes. 256 ⇒ ≤ 4 longs of
         *  bitset (32 bytes), which beats a holes array once roughly 8+ values get
         *  excluded; below that the bitset still wins on per-op cost (O(1) test vs
         *  O(log holes) binary search). */
        const val BITSET_THRESHOLD: Int = 256

        private fun trimHolesBelow(holes: IntArray?, upper: Int): IntArray? {
            if (holes == null) return null
            var start = 0
            while (start < holes.size && holes[start] < upper) start++
            return when {
                start == holes.size -> null
                start == 0 -> holes
                else -> holes.copyOfRange(start, holes.size)
            }
        }

        private fun trimHolesAbove(holes: IntArray?, upper: Int): IntArray? {
            if (holes == null) return null
            var end = holes.size
            while (end > 0 && holes[end - 1] > upper) end--
            return when {
                end == 0 -> null
                end == holes.size -> holes
                else -> holes.copyOfRange(0, end)
            }
        }

        private fun insertSorted(holes: IntArray, value: Int): IntArray {
            val idx = holes.binarySearchInt(value)
            if (idx >= 0) return holes
            val insertAt = -(idx + 1)
            val out = IntArray(holes.size + 1)
            for (i in 0 until insertAt) out[i] = holes[i]
            out[insertAt] = value
            for (i in insertAt until holes.size) out[i + 1] = holes[i]
            return out
        }

        /** Set bits `[from, to)` in [bits] (relative to the bitset's offset). */
        private fun fillBitsetRange(bits: LongArray, from: Int, to: Int) {
            if (from >= to) return
            val firstWord = from ushr 6
            val lastWord = (to - 1) ushr 6
            val firstBit = from and 63
            val lastBit = (to - 1) and 63
            if (firstWord == lastWord) {
                val mask = ((1L shl (lastBit - firstBit + 1)) - 1L).let {
                    if (lastBit - firstBit + 1 == 64) -1L else it
                } shl firstBit
                bits[firstWord] = bits[firstWord] or mask
                return
            }
            bits[firstWord] = bits[firstWord] or (-1L shl firstBit)
            for (w in firstWord + 1 until lastWord) bits[w] = -1L
            val tailMask = if (lastBit == 63) -1L else ((1L shl (lastBit + 1)) - 1L)
            bits[lastWord] = bits[lastWord] or tailMask
        }

        @PublishedApi
        internal fun clearBit(bits: LongArray, bit: Int) {
            val w = bit ushr 6
            val b = bit and 63
            bits[w] = bits[w] and (1L shl b).inv()
        }

        @PublishedApi
        internal fun bitsetHasBit(bits: LongArray, bit: Int): Boolean {
            val w = bit ushr 6
            val b = bit and 63
            return ((bits[w] ushr b) and 1L) != 0L
        }

        /** Clear all bits with index `< exclusiveBit`. */
        @PublishedApi
        internal fun clearBitsBelow(bits: LongArray, exclusiveBit: Int) {
            if (exclusiveBit <= 0) return
            val fullWords = exclusiveBit ushr 6
            val rem = exclusiveBit and 63
            val limit = minOf(fullWords, bits.size)
            for (w in 0 until limit) bits[w] = 0L
            if (fullWords < bits.size && rem > 0) {
                val mask = ((1L shl rem) - 1L).inv()
                bits[fullWords] = bits[fullWords] and mask
            }
        }

        /** Clear all bits with index `> inclusiveBit`. */
        @PublishedApi
        internal fun clearBitsAbove(bits: LongArray, inclusiveBit: Int) {
            val fromWord = (inclusiveBit + 1) ushr 6
            val rem = (inclusiveBit + 1) and 63
            if (fromWord < bits.size && rem > 0) {
                val mask = (1L shl rem) - 1L
                bits[fromWord] = bits[fromWord] and mask
                for (w in fromWord + 1 until bits.size) bits[w] = 0L
            } else {
                for (w in fromWord until bits.size) bits[w] = 0L
            }
        }

        @PublishedApi
        internal fun firstSetBit(bits: LongArray): Int {
            for (w in bits.indices) {
                if (bits[w] != 0L) return (w shl 6) + bits[w].countTrailingZeroBits()
            }
            return -1
        }

        @PublishedApi
        internal fun lastSetBit(bits: LongArray): Int {
            for (w in bits.indices.reversed()) {
                if (bits[w] != 0L) return (w shl 6) + (63 - bits[w].countLeadingZeroBits())
            }
            return -1
        }
    }
}
