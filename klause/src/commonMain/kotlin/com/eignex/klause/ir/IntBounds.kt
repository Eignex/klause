package com.eignex.klause.ir

import com.eignex.klause.util.Bits
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Integer bounds that may be open on either side.
 *
 * A finite CP domain is deliberately finite: it is the value set the search can branch on. This table
 * instead describes the model-level range a theory or relaxation sees. Endpoints are stored in primitive
 * arrays and open sides are packed into two bitsets, so a million-variable problem does not allocate a
 * bound object or a [BigInteger] per column.
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
    fun lowerAsBigInteger(v: Int): BigInteger? = if (hasLower(v)) BigInteger.fromLong(lower(v)) else null

    /** Upper bound of column [v], widened on demand, or `null` when open. */
    fun upperAsBigInteger(v: Int): BigInteger? = if (hasUpper(v)) BigInteger.fromLong(upper(v)) else null

    internal val openLowerBits: Bits? get() = openLo

    internal val openUpperBits: Bits? get() = openHi

    /** A [Tightening] over a copy of these ranges, for a pass that proves bounds on some of the columns. */
    internal fun tightening(): Tightening =
        Tightening(lowerBounds.copyOf(), upperBounds.copyOf(), openLo?.copy(), openHi?.copy())

    /**
     * The first column whose proved range admits nothing, or -1.
     *
     * A crossed range is a refutation, and it has to be read here rather than left to whoever
     * materializes a domain from it: two bounds proved from different rows can cross without either one
     * being wrong, so the caller reports `unsat` where a domain would only throw.
     */
    internal fun crossedColumn(): Int {
        for (v in 0 until size) if (hasLower(v) && hasUpper(v) && lowerBounds[v] > upperBounds[v]) return v
        return -1
    }

    /**
     * Bounds under construction, narrowed column by column before being stated as one [IntBounds].
     *
     * The ranges are copied once and mutated in place, so proving a bound on a handful of columns of a
     * wide model costs one copy rather than one per column. Only narrowing is possible: [atLeast] and
     * [atMost] keep whichever of the proved and the current bound is tighter, and closing an open side
     * counts as narrowing however wide the proved bound is. A pass that ends with [changed] false proved
     * nothing its input did not already state.
     */
    internal class Tightening internal constructor(
        private val lowerBounds: LongArray,
        private val upperBounds: LongArray,
        private var openLo: Bits?,
        private var openHi: Bits?,
    ) {
        private var built = false

        /** Whether any call actually narrowed a range. */
        var changed: Boolean = false
            private set

        /** Raise column [v]'s lower bound to [value], or close its open lower side at [value]. */
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

        /** Lower column [v]'s upper bound to [value], or close its open upper side at [value]. */
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

        /** Finish this tightening and return its ranges, or null when nothing was narrowed. */
        fun build(): IntBounds? {
            check(!built) { "bounds already built" }
            built = true
            return if (!changed) null else fromModelBounds(lowerBounds, upperBounds, openLo, openHi)
        }
    }

    /** Internal constructor for source-model storage. */
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
