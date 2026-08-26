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
