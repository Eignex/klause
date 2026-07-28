package com.eignex.klause.solver.intdomain

import com.eignex.klause.solver.IntConsumer
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.Bits

/** Bitset over a narrow span: bit `(value - bitsetLo)` is set iff `value` is present. The backing
 *  array keeps its construction-time length; a later bound move clears bits but never reallocates,
 *  so the surplus high words are always zero. */
internal class BitsetDomain(
    override val min: Long,
    override val max: Long,
    private val bitset: LongArray,
    private val bitsetLo: Long,
) : AbstractIntDomain() {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
        // The endpoints must be members — a violation here means a caller built the bit array wrong
        // (the span-overflow class of bug), and catching it at construction names the culprit.
        check(Bits.has(bitset, (min - bitsetLo).toInt())) { "min $min is not a member (lo=$bitsetLo)" }
        check(Bits.has(bitset, (max - bitsetLo).toInt())) { "max $max is not a member (lo=$bitsetLo)" }
    }

    override val size: Int = run {
        var s = 0
        for (w in bitset.indices) s += bitset[w].countOneBits()
        s
    }

    override fun contains(value: Long): Boolean = value in min..max && Bits.has(bitset, (value - bitsetLo).toInt())

    override fun valueAt(i: Int): Long {
        var remaining = i
        for (w in bitset.indices) {
            val word = bitset[w]
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

    override fun excludeValue(value: Long): IntDomain {
        if (!contains(value)) return this
        val newBits = bitset.copyOf()
        Bits.clear(newBits, (value - bitsetLo).toInt())
        var newMin = min
        var newMax = max
        if (value == min) {
            val firstSet = Bits.firstSet(newBits)
            check(firstSet >= 0) { "Empty domain after excludeValue($value)" }
            newMin = bitsetLo + firstSet
        }
        if (value == max) {
            val lastSet = Bits.lastSet(newBits)
            check(lastSet >= 0) { "Empty domain after excludeValue($value)" }
            newMax = bitsetLo + lastSet
        }
        return BitsetDomain(newMin, newMax, newBits, bitsetLo)
    }

    override fun withMinAtLeast(newMin: Long): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        val newBits = bitset.copyOf()
        Bits.clearBelow(newBits, (newMin - bitsetLo).toInt())
        val firstSet = Bits.firstSet(newBits)
        check(firstSet >= 0) { "withMinAtLeast($newMin) emptied bitset domain" }
        val m = bitsetLo + firstSet
        check(m <= max) { "withMinAtLeast($newMin): only zero bits remained" }
        return BitsetDomain(m, max, newBits, bitsetLo)
    }

    override fun withMaxAtMost(newMax: Long): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        val newBits = bitset.copyOf()
        Bits.clearAbove(newBits, (newMax - bitsetLo).toInt())
        val lastSet = Bits.lastSet(newBits)
        check(lastSet >= 0) { "withMaxAtMost($newMax) emptied bitset domain" }
        val m = bitsetLo + lastSet
        check(m >= min) { "withMaxAtMost($newMax): only zero bits remained" }
        return BitsetDomain(min, m, newBits, bitsetLo)
    }

    override fun includeInteriorValue(value: Long): IntDomain {
        require(value > min && value < max) { "includeInteriorValue($value) outside ($min, $max)" }
        val newBits = bitset.copyOf()
        Bits.set(newBits, (value - bitsetLo).toInt())
        return BitsetDomain(min, max, newBits, bitsetLo)
    }

    override fun forEach(action: IntConsumer) {
        for (w in bitset.indices) {
            var word = bitset[w]
            while (word != 0L) {
                val lsb = word.countTrailingZeroBits()
                action.accept(bitsetLo + (w shl 6) + lsb)
                word = word and (word - 1L)
            }
        }
    }

    override fun forEachHole(action: IntConsumer) {
        var v = min + 1
        while (v < max) {
            if (!Bits.has(bitset, (v - bitsetLo).toInt())) action.accept(v)
            v++
        }
    }

    override fun forEachHoleInRange(lo: Long, hi: Long, action: IntConsumer) {
        val from = if (lo > min) lo else min
        val to = if (hi < max) hi else max
        if (from > to) return
        // Holes are the *zero* bits in [from, to]. Iterate word by word and emit the set bits of the
        // inverted (range-masked) word: an all-ones word inverts to 0 and is skipped in one step, so a
        // dense domain with few holes costs O(span/64 + holes) instead of a value-by-value O(span) scan.
        val fromBit = (from - bitsetLo).toInt()
        val toBit = (to - bitsetLo).toInt()
        val firstWord = fromBit ushr 6
        val lastWord = toBit ushr 6
        for (w in firstWord..lastWord) {
            val lowBit = if (w == firstWord) fromBit and 63 else 0
            val highBit = if (w == lastWord) toBit and 63 else 63
            val lowMask = -1L shl lowBit
            val highMask = if (highBit == 63) -1L else (1L shl (highBit + 1)) - 1L
            var holes = bitset[w].inv() and lowMask and highMask
            val base = bitsetLo + (w.toLong() shl 6)
            while (holes != 0L) {
                action.accept(base + holes.countTrailingZeroBits())
                holes = holes and (holes - 1L)
            }
        }
    }

    override fun toString(): String {
        val sb = StringBuilder("IntDomain($min..$max bitset[")
        var first = true
        forEach { v ->
            if (!first) sb.append(",")
            sb.append(v)
            first = false
        }
        return sb.append("])").toString()
    }
}
