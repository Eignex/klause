package com.eignex.klause.solver.intdomain

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.solver.IntConsumer
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.IntSpan
import com.eignex.klause.util.Bits

/** Contiguous `(min..max)`, no interior holes. */
internal class ContiguousDomain(override val min: Long, override val max: Long) :
    AbstractIntDomain(),
    IntSpan {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
    }

    // Saturates at Int.MAX_VALUE for spans beyond 32-bit: a wide contiguous domain is never enumerated,
    // so callers that read size on it want "very large", not an exact (unrepresentable) count. A full
    // Long.MIN..Long.MAX span overflows `max - min` to a negative value, so treat a negative span as
    // "very large" too rather than letting it wrap to a bogus small count.
    override fun spanOrNull(maxValues: Long): IntSpan? {
        val span = max - min
        // Never past Int range whatever the caller asks for: a span indexes its values with an Int.
        val cap = minOf(maxValues, Int.MAX_VALUE.toLong())
        return if (span >= 0 && span + 1 <= cap) this else null
    }

    override val size: Int get() {
        val span = max - min
        return if (span < 0L || span >= Int.MAX_VALUE.toLong()) Int.MAX_VALUE else (span + 1L).toInt()
    }

    private val enumerable: Boolean get() {
        val span = max - min
        return span in 0L until Int.MAX_VALUE.toLong()
    }

    private val exactValueCount: Long get() {
        val span = max - min
        return if (span < 0L || span == Long.MAX_VALUE) Long.MAX_VALUE else span + 1
    }

    override val holeCount: Long get() = 0

    override fun contains(value: Long): Boolean = value in min..max

    override fun valueAt(i: Int): Long = min + i

    override fun excludeValue(value: Long): IntDomain {
        if (value !in min..max) return this
        return when (value) {
            min -> ContiguousDomain(min + 1, max)

            max -> ContiguousDomain(min, max - 1)

            else -> {
                // A full-Long span overflows the subtraction; a negative "span" means huge, never bitset.
                val span = max - min + 1
                if (span in 1..KlauseConfig.current.bitsetThreshold) {
                    val spanI = span.toInt()
                    val bits = LongArray((spanI + 63) ushr 6)
                    Bits.fillRange(bits, 0, spanI)
                    Bits.clear(bits, (value - min).toInt())
                    BitsetDomain(min, max, bits, min)
                } else {
                    RunsDomain(min, max, longArrayOf(min, value - 1, value + 1, max))
                }
            }
        }
    }

    override fun withMinAtLeast(newMin: Long): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        return ContiguousDomain(newMin, max)
    }

    override fun withMaxAtMost(newMax: Long): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        return ContiguousDomain(min, newMax)
    }

    override fun includeInteriorValue(value: Long): IntDomain =
        error("includeInteriorValue($value) on a contiguous domain")

    override fun forEach(action: IntConsumer) {
        var v = min
        while (v <= max) {
            action.accept(v)
            v++
        }
    }

    override fun forEachHole(action: IntConsumer) = Unit

    override fun forEachHoleInRange(lo: Long, hi: Long, action: IntConsumer) = Unit

    override fun toString(): String = "IntDomain($min..$max)"
}
