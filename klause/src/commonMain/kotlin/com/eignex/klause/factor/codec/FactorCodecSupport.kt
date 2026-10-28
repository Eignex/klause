package com.eignex.klause.factor.codec

import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.util.BigInt

// Integral rows write 64-bit constants as numbers and wider ones as decimal strings under `wide…` fields,
// so a document keeps the width a row was built at and a reader sees which arithmetic it needs.

internal val IntegralConstants.longCoefficients: LongArray? get() = (this as? IntegerConstants)?.coeffs

internal val IntegralConstants.longBound: Long? get() = (this as? IntegerConstants)?.bound

internal val IntegralConstants.wideCoefficients: List<BigInt>?
    get() = (this as? WideConstants)?.coefficients?.toTypedArray()?.toList()

internal val IntegralConstants.wideBound: BigInt? get() = (this as? WideConstants)?.bound

/** Rebuild from whichever of the two constant groups a payload states; exactly one must be complete. */
internal inline fun <T> integralRow(
    coefficients: LongArray?,
    bound: Long?,
    wideCoefficients: List<BigInt>?,
    wideBound: BigInt?,
    long: (LongArray, Long) -> T,
    wide: (Array<BigInt>, BigInt) -> T,
): T {
    val stated = (coefficients != null) to (bound != null)
    val statedWide = (wideCoefficients != null) to (wideBound != null)
    val complete = stated.first == stated.second && statedWide.first == statedWide.second
    require(complete && stated.first != statedWide.first) {
        "state either 'coefficients' and 'bound' or 'wideCoefficients' and 'wideBound'"
    }
    return if (wideCoefficients != null && wideBound != null) {
        wide(wideCoefficients.toTypedArray(), wideBound)
    } else {
        long(requireNotNull(coefficients), requireNotNull(bound))
    }
}

/** A finite double or `null` for the infinity on [side] (`-1` below, `1` above). */
internal fun Double.finiteOrNull(side: Int): Double? {
    require(!isNaN() && (isFinite() || (side < 0) == (this < 0))) { "bound $this is not finite or open" }
    return takeIf { it.isFinite() }
}

/** The infinity on [side] when this side is open. */
internal fun Double?.orInfinity(side: Int): Double =
    this ?: if (side < 0) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
