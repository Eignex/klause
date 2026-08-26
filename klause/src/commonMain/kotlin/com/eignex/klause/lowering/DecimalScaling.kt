package com.eignex.klause.lowering

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Multiplier carrying a row of decimal-written values onto whole numbers.
 *
 * A format whose numbers are decimal text can state a fractional coefficient on a row whose variables
 * are integers, and the row's factor holds `Long` coefficients. The multiplier is the least common
 * denominator of the row's values — always a power of ten, because that is the denominator every
 * written decimal has — so an exact multiplier restates the row rather than approximating it.
 */
internal sealed interface RowScale {

    /** The power of ten every value is multiplied by. */
    val multiplier: Long

    /** Multiply [value] onto the scale. Exact for [Exact]; the nearest whole number for [Rounded]. */
    fun scale(value: Double): Long = (value * multiplier.toDouble()).roundToLong()

    /** Every value of the row is whole at [multiplier]: the scaled row restates the source text. */
    class Exact(override val multiplier: Long) : RowScale

    /**
     * No power of ten makes the row whole within the exactly-recoverable range, so [multiplier] is the
     * finest the row's magnitudes admit and every value is rounded onto it.
     *
     * This is a field printed at full double precision — `-0.0214054799999994` for `-0.02140548`, or a
     * rational truncated to its leading digits — where the trailing digits are an artifact of printing
     * a double and there is no source decimal to recover.
     */
    class Rounded(override val multiplier: Long) : RowScale

    /**
     * The row spans a wider dynamic range than any single multiplier covers: the multiplier that keeps
     * the largest term inside the recoverable range sends the smallest term to zero, which would drop
     * it from the row instead of approximating it.
     */
    data object Unrepresentable : RowScale {
        override val multiplier: Long get() = 1L
    }
}

/**
 * Accumulates one row's values and resolves the power of ten that makes them whole.
 *
 * Values are observed in any order and the scale is resolved once, because the multiplier depends on
 * the whole row: a row is scaled by a single factor to stay equivalent.
 */
internal class RowScaleBuilder {

    private var places = 0
    private var largest = 0.0
    private var smallestNonZero = Double.MAX_VALUE
    private var recoverable = true

    /** Fold [value] into the row. A non-finite value carries no scale and is skipped. */
    fun observe(value: Double) {
        if (!isFiniteValue(value)) return
        val magnitude = abs(value)
        if (magnitude > largest) largest = magnitude
        if (magnitude != 0.0 && magnitude < smallestNonZero) smallestNonZero = magnitude
        val needed = decimalPlacesOf(value)
        if (needed < 0) recoverable = false else if (needed > places) {
            places = needed
        }
    }

    /** The scale carrying every observed value onto whole numbers. */
    fun resolve(): RowScale {
        if (largest == 0.0) return RowScale.Exact(1L)
        if (recoverable) {
            val exact = POWERS_OF_TEN[places]
            if (largest * exact.toDouble() <= MAX_UNITS) return RowScale.Exact(exact)
        }
        val fitted = largestFittingPower(largest)
        if (smallestNonZero * fitted.toDouble() < 0.5) return RowScale.Unrepresentable
        return RowScale.Rounded(fitted)
    }
}

/**
 * Smallest decimal place count reproducing [value] exactly — `units / 10^places` is [value] itself,
 * not merely close to it — or `-1` when no decimal within [MAX_PLACES] places round-trips to it.
 */
private fun decimalPlacesOf(value: Double): Int {
    if (value == 0.0) return 0
    for (places in 0..MAX_PLACES) {
        val power = POWERS_OF_TEN[places].toDouble()
        val units = value * power
        if (abs(units) > MAX_UNITS) break
        if (units.roundToLong().toDouble() / power == value) return places
    }
    return -1
}

/** The largest power of ten keeping [largest] inside the exactly-recoverable range. */
private fun largestFittingPower(largest: Double): Long {
    var places = 0
    while (places < MAX_PLACES && largest * POWERS_OF_TEN[places + 1].toDouble() <= MAX_UNITS) places++
    return POWERS_OF_TEN[places]
}

private fun isFiniteValue(value: Double): Boolean = value - value == 0.0

/**
 * Magnitude bound for a scaled value. `value * 10^places` is computed in double, so both operands and
 * the product each carry a rounding of at most `2^-53`; below this bound the accumulated error stays
 * under half a unit and [kotlin.math.roundToLong] recovers the whole number the text names.
 */
private const val MAX_UNITS = (1L shl 51).toDouble()

/** Decimal places past which a field carries no recoverable source decimal, only double noise. */
private const val MAX_PLACES = 15

private val POWERS_OF_TEN = LongArray(MAX_PLACES + 2).also {
    var power = 1L
    for (i in it.indices) {
        it[i] = power
        if (i < it.size - 1) power *= 10L
    }
}
