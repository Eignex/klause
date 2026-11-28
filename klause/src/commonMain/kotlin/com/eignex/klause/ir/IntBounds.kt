package com.eignex.klause.ir

import com.eignex.util.collections.Bits
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf

/**
 * Integer bounds that may be open on either side.
 *
 * A finite CP domain is deliberately finite: it is the value set the search can branch on. This table
 * instead describes the model-level range a theory or relaxation sees. Endpoints are stored in primitive
 * arrays and open sides are packed into two bitsets, so a million-variable problem does not allocate a
 * bound object or a [BigInt] per column.
 * Exact consumers can call [lowerAsBigInteger] or [upperAsBigInteger] only for the columns they inspect.
 */
class IntBounds internal constructor(
    private val lowerBounds: LongArray,
    private val upperBounds: LongArray,
    private val openLo: Bits?,
    private val openHi: Bits?,
) {
    /** Number of integer columns. */
    val size: Int get() = lowerBounds.size

    internal val hasOpenSides: Boolean by lazy {
        (openLo?.cardinality() ?: 0) > 0 || (openHi?.cardinality() ?: 0) > 0
    }

    /** Whether column [v] has a finite lower bound. */
    fun hasLower(v: Int): Boolean = !isOpenLower(v)

    /** Whether column [v] has a finite upper bound. */
    fun hasUpper(v: Int): Boolean = !isOpenUpper(v)

    /** Whether column [v] is open below. */
    fun isOpenLower(v: Int): Boolean = openLo?.get(v) == true

    /** Whether column [v] is open above. */
    fun isOpenUpper(v: Int): Boolean = openHi?.get(v) == true

    /** Finite lower bound of column [v]. Throws when that side is open. */
    fun lower(v: Int): Long {
        check(hasLower(v)) { "integer column $v has no lower bound" }
        return lowerBounds[v]
    }

    /** Finite upper bound of column [v]. Throws when that side is open. */
    fun upper(v: Int): Long {
        check(hasUpper(v)) { "integer column $v has no upper bound" }
        return upperBounds[v]
    }

    /** Lower bound of column [v], widened on demand, or `null` when open. */
    fun lowerAsBigInteger(v: Int): BigInt? = if (hasLower(v)) bigIntOf(lower(v)) else null

    /** Upper bound of column [v], widened on demand, or `null` when open. */
    fun upperAsBigInteger(v: Int): BigInt? = if (hasUpper(v)) bigIntOf(upper(v)) else null

    internal val openLowerBits: Bits? get() = openLo

    internal val openUpperBits: Bits? get() = openHi

    internal fun tightening(): Tightening =
        Tightening(lowerBounds.copyOf(), upperBounds.copyOf(), openLo?.copy(), openHi?.copy())

    // A crossed range is a refutation and is read here rather than left to whoever materializes a domain:
    // two bounds proved from different rows can cross, so the caller reports `unsat` where a domain throws.
    internal fun crossedColumn(): Int {
        for (v in 0 until size) if (hasLower(v) && hasUpper(v) && lowerBounds[v] > upperBounds[v]) return v
        return -1
    }

    // Copied once and mutated in place, so proving bounds on a few columns of a wide model costs one copy.
    // Only narrowing is possible; closing an open side counts as narrowing however wide the proved bound.
    internal class Tightening internal constructor(
        private val lowerBounds: LongArray,
        private val upperBounds: LongArray,
        private var openLo: Bits?,
        private var openHi: Bits?,
    ) {
        private var built = false

        var changed: Boolean = false
            private set

        fun atLeast(v: Int, value: Long) {
            check(!built) { "cannot tighten bounds after build" }
            if (openLo?.get(v) == true) {
                openLo?.clear(v)
            } else if (value <= lowerBounds[v]) {
                return
            }
            lowerBounds[v] = value
            changed = true
        }

        fun atMost(v: Int, value: Long) {
            check(!built) { "cannot tighten bounds after build" }
            if (openHi?.get(v) == true) {
                openHi?.clear(v)
            } else if (value >= upperBounds[v]) {
                return
            }
            upperBounds[v] = value
            changed = true
        }

        fun build(): IntBounds? {
            check(!built) { "bounds already built" }
            built = true
            return if (!changed) null else fromModelBounds(lowerBounds, upperBounds, openLo, openHi)
        }
    }

    /** Holds the internal factories for source-model storage. */
    companion object {
        internal fun fromFiniteBounds(
            lowerBounds: LongArray,
            upperBounds: LongArray,
            openLo: BooleanArray?,
            openHi: BooleanArray?,
            packedOpenLo: Bits?,
            packedOpenHi: Bits?,
        ): IntBounds = IntBounds(
            lowerBounds = lowerBounds,
            upperBounds = upperBounds,
            openLo = packedOpenLo ?: openLo?.toBits(),
            openHi = packedOpenHi ?: openHi?.toBits(),
        )

        internal fun fromModelBounds(
            lowerBounds: LongArray,
            upperBounds: LongArray,
            openLo: Bits?,
            openHi: Bits?,
        ): IntBounds {
            require(lowerBounds.size == upperBounds.size)
            return IntBounds(
                lowerBounds = lowerBounds,
                upperBounds = upperBounds,
                openLo = openLo,
                openHi = openHi,
            )
        }

        private fun BooleanArray.toBits(): Bits? {
            if (none { it }) return null
            return Bits(size).also { bits -> for (i in indices) if (this[i]) bits.set(i) }
        }
    }
}
