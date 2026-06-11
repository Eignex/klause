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

    // Single-bit get/set/clear sit on the propagation hot path — every BCP literal touch reads a
    // bit, millions of times per solve — so they carry no bounds check. Callers pass in-range ids
    // (`< size`), matching the unchecked-primitive convention of IntArrayList; an out-of-range
    // index still faults via the backing-array access rather than silently corrupting state.
    fun get(i: Int): Boolean = (words[i ushr 6] ushr (i and 63)) and 1L == 1L

    fun set(i: Int) {
        words[i ushr 6] = words[i ushr 6] or (1L shl (i and 63))
    }

    fun clear(i: Int) {
        words[i ushr 6] = words[i ushr 6] and (1L shl (i and 63)).inv()
    }

    fun cardinality(): Int {
        var c = 0
        for (w in words) c += w.countOneBits()
        return c
    }

    /** Logical OR in place: `this |= other`. Both bitsets must have the same [size]. */
    fun orInPlace(other: Bits) {
        require(size == other.size)
        for (i in words.indices) words[i] = words[i] or other.words[i]
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

    /** True iff every set bit of [other] is also set in `this` (i.e. `other ⊆ this`). */
    fun containsAll(other: Bits): Boolean {
        require(size == other.size)
        for (i in words.indices) if ((words[i] and other.words[i]) != other.words[i]) return false
        return true
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
