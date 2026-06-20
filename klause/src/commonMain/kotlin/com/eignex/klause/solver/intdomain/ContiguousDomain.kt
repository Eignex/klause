package com.eignex.klause.solver.intdomain

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.solver.IntConsumer
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.Bits

/** Contiguous `(min..max)`, no interior holes. */
internal class ContiguousDomain(override val min: Int, override val max: Int) : AbstractIntDomain() {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
    }

    override val size: Int get() = max - min + 1

    override fun contains(value: Int): Boolean = value in min..max

    override fun valueAt(i: Int): Int = min + i

    override fun excludeValue(value: Int): IntDomain {
        if (value !in min..max) return this
        return when (value) {
            min -> ContiguousDomain(min + 1, max)

            max -> ContiguousDomain(min, max - 1)

            else -> {
                val span = max - min + 1
                if (span <= KlauseConfig.current.bitsetThreshold) {
                    val bits = LongArray((span + 63) ushr 6)
                    Bits.fillRange(bits, 0, span)
                    Bits.clear(bits, value - min)
                    BitsetDomain(min, max, bits, min)
                } else {
                    RunsDomain(min, max, intArrayOf(min, value - 1, value + 1, max))
                }
            }
        }
    }

    override fun withMinAtLeast(newMin: Int): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        return ContiguousDomain(newMin, max)
    }

    override fun withMaxAtMost(newMax: Int): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        return ContiguousDomain(min, newMax)
    }

    override fun includeInteriorValue(value: Int): IntDomain =
        error("includeInteriorValue($value) on a contiguous domain")

    override fun forEach(action: IntConsumer) {
        for (v in min..max) action.accept(v)
    }

    override fun forEachHole(action: IntConsumer) = Unit

    override fun forEachHoleInRange(lo: Int, hi: Int, action: IntConsumer) = Unit

    override fun toString(): String = "IntDomain($min..$max)"
}
