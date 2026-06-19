package com.eignex.klause.util

/**
 * Minimal multiplatform bitset over `[0, size)` ints, backed by a [LongArray]. Carved out
 * because Kotlin's common stdlib has no `BitSet`. Used by `SetDomain` / propagation set
 * arrays — every operation we need (membership, set / clear, cardinality, union, intersect,
 * difference, equality, copy) is a one-liner over the packed words.
 *
 * Not exposed beyond the solver package: the surface stays small on purpose.
 */
internal class Bits(val size: Int) {
    @PublishedApi internal val words: LongArray = LongArray((size + 63) ushr 6)

    // Single-bit get/set/clear sit on the propagation hot path  so no checks
    fun get(i: Int): Boolean = (words[i ushr 6] ushr (i and 63)) and 1L == 1L

    fun set(i: Int) {
        words[i ushr 6] = words[i ushr 6] or (1L shl (i and 63))
    }

    fun clear(i: Int) {
        words[i ushr 6] = words[i ushr 6] and (1L shl (i and 63)).inv()
    }

    /** Iterate over set bits in ascending order. */
    inline fun forEachSet(block: (Int) -> Unit) {
        for (wi in words.indices) {
            var w = words[wi]
            while (w != 0L) {
                val bit = w.countTrailingZeroBits()
                block((wi shl 6) + bit)
                w = w and (w - 1)
            }
        }
    }

    fun cardinality(): Int {
        var c = 0
        for (w in words) c += w.countOneBits()
        return c
    }

    fun toIntArray(): IntArray {
        val out = IntArray(cardinality())
        var k = 0
        forEachSet { out[k++] = it }
        return out
    }

    fun copy(): Bits {
        val out = Bits(size)
        for (i in words.indices) out.words[i] = words[i]
        return out
    }

    /** Copy [other] into this in place. Both must have the same [size]. */
    fun copyFrom(other: Bits) {
        require(size == other.size)
        for (i in words.indices) words[i] = other.words[i]
    }

    /** True iff every set bit of [other] is also set in `this` (i.e. `other ⊆ this`). */
    fun containsAll(other: Bits): Boolean {
        require(size == other.size)
        for (i in words.indices) if ((words[i] and other.words[i]) != other.words[i]) return false
        return true
    }

    /** Logical AND in place: `this &= other`. Both bitsets must have the same [size]. */
    fun andInPlace(other: Bits) {
        require(size == other.size)
        for (i in words.indices) words[i] = words[i] and other.words[i]
    }

    /** Logical AND-NOT in place: `this &= ~other`. Both bitsets must have the same [size]. */
    fun andNotInPlace(other: Bits) {
        require(size == other.size)
        for (i in words.indices) words[i] = words[i] and other.words[i].inv()
    }

    /** Logical OR in place: `this |= other`. Both bitsets must have the same [size]. */
    fun orInPlace(other: Bits) {
        require(size == other.size)
        for (i in words.indices) words[i] = words[i] or other.words[i]
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Bits) return false
        if (size != other.size) return false
        return words.contentEquals(other.words)
    }

    override fun hashCode(): Int = 31 * size + words.contentHashCode()

    override fun toString(): String {
        val sb = StringBuilder("{")
        var first = true
        forEachSet {
            if (!first) sb.append(", ")
            sb.append(it)
            first = false
        }
        sb.append('}')
        return sb.toString()
    }

    companion object {
        fun has(bits: LongArray, bit: Int): Boolean = ((bits[bit ushr 6] ushr (bit and 63)) and 1L) != 0L

        fun set(bits: LongArray, bit: Int) {
            bits[bit ushr 6] = bits[bit ushr 6] or (1L shl (bit and 63))
        }

        fun clear(bits: LongArray, bit: Int) {
            val w = bit ushr 6
            bits[w] = bits[w] and (1L shl (bit and 63)).inv()
        }

        /** Set bits `[from, to)`. */
        fun fillRange(bits: LongArray, from: Int, to: Int) {
            if (from >= to) return
            val firstWord = from ushr 6
            val lastWord = (to - 1) ushr 6
            val firstBit = from and 63
            val lastBit = (to - 1) and 63
            if (firstWord == lastWord) {
                val width = lastBit - firstBit + 1
                val mask = (if (width == 64) -1L else (1L shl width) - 1L) shl firstBit
                bits[firstWord] = bits[firstWord] or mask
                return
            }
            bits[firstWord] = bits[firstWord] or (-1L shl firstBit)
            for (w in firstWord + 1 until lastWord) bits[w] = -1L
            val tailMask = if (lastBit == 63) -1L else (1L shl (lastBit + 1)) - 1L
            bits[lastWord] = bits[lastWord] or tailMask
        }

        /** Clear all bits with index `< exclusiveBit`. */
        fun clearBelow(bits: LongArray, exclusiveBit: Int) {
            if (exclusiveBit <= 0) return
            val fullWords = exclusiveBit ushr 6
            val rem = exclusiveBit and 63
            val limit = minOf(fullWords, bits.size)
            for (w in 0 until limit) bits[w] = 0L
            if (fullWords < bits.size && rem > 0) {
                bits[fullWords] = bits[fullWords] and ((1L shl rem) - 1L).inv()
            }
        }

        /** Clear all bits with index `> inclusiveBit`. */
        fun clearAbove(bits: LongArray, inclusiveBit: Int) {
            val fromWord = (inclusiveBit + 1) ushr 6
            val rem = (inclusiveBit + 1) and 63
            if (fromWord < bits.size && rem > 0) {
                bits[fromWord] = bits[fromWord] and ((1L shl rem) - 1L)
                for (w in fromWord + 1 until bits.size) bits[w] = 0L
            } else {
                for (w in fromWord until bits.size) bits[w] = 0L
            }
        }

        fun firstSet(bits: LongArray): Int {
            for (w in bits.indices) {
                if (bits[w] != 0L) return (w shl 6) + bits[w].countTrailingZeroBits()
            }
            return -1
        }

        fun lastSet(bits: LongArray): Int {
            for (w in bits.indices.reversed()) {
                if (bits[w] != 0L) return (w shl 6) + (63 - bits[w].countLeadingZeroBits())
            }
            return -1
        }

        fun empty(size: Int): Bits = Bits(size)

        fun full(size: Int): Bits {
            val b = Bits(size)
            // Set every bit up to size, then zero the tail in the last word.
            for (i in b.words.indices) b.words[i] = -1L
            val tail = size and 63
            if (tail != 0) {
                b.words[b.words.size - 1] = b.words[b.words.size - 1] and ((1L shl tail) - 1L)
            }
            return b
        }

        fun of(size: Int, elements: IntArray): Bits {
            val b = Bits(size)
            for (e in elements) b.set(e)
            return b
        }
    }
}
