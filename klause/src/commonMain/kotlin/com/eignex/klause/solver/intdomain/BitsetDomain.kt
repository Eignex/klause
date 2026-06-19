package com.eignex.klause.solver.intdomain

import com.eignex.klause.solver.IntConsumer
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.Bits

/** Bitset over a narrow span: bit `(value - bitsetLo)` is set iff `value` is present. The backing
 *  array keeps its construction-time length; a later bound move clears bits but never reallocates,
 *  so the surplus high words are always zero. */
internal class BitsetDomain(
    override val min: Int,
    override val max: Int,
    private val bitset: LongArray,
    private val bitsetLo: Int,
) : AbstractIntDomain() {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
    }

    override val size: Int = run {
        var s = 0
        for (w in bitset.indices) s += bitset[w].countOneBits()
        s
    }

    override fun contains(value: Int): Boolean = value in min..max && Bits.has(bitset, value - bitsetLo)

    override fun valueAt(i: Int): Int {
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

    override fun excludeValue(value: Int): IntDomain {
        if (!contains(value)) return this
        val newBits = bitset.copyOf()
        Bits.clear(newBits, value - bitsetLo)
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

    override fun withMinAtLeast(newMin: Int): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        val newBits = bitset.copyOf()
        Bits.clearBelow(newBits, newMin - bitsetLo)
        val firstSet = Bits.firstSet(newBits)
        check(firstSet >= 0) { "withMinAtLeast($newMin) emptied bitset domain" }
        val m = bitsetLo + firstSet
        check(m <= max) { "withMinAtLeast($newMin): only zero bits remained" }
        return BitsetDomain(m, max, newBits, bitsetLo)
    }

    override fun withMaxAtMost(newMax: Int): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        val newBits = bitset.copyOf()
        Bits.clearAbove(newBits, newMax - bitsetLo)
        val lastSet = Bits.lastSet(newBits)
        check(lastSet >= 0) { "withMaxAtMost($newMax) emptied bitset domain" }
        val m = bitsetLo + lastSet
        check(m >= min) { "withMaxAtMost($newMax): only zero bits remained" }
        return BitsetDomain(min, m, newBits, bitsetLo)
    }

    override fun includeInteriorValue(value: Int): IntDomain {
        require(value > min && value < max) { "includeInteriorValue($value) outside ($min, $max)" }
        val newBits = bitset.copyOf()
        Bits.set(newBits, value - bitsetLo)
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
        for (v in (min + 1) until max) {
            if (!Bits.has(bitset, v - bitsetLo)) action.accept(v)
        }
    }

    override fun forEachHoleInRange(lo: Int, hi: Int, action: IntConsumer) {
        val from = if (lo > min) lo else min
        val to = if (hi < max) hi else max
        var v = from
        while (v <= to) {
            if (!Bits.has(bitset, v - bitsetLo)) action.accept(v)
            v++
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
