package com.eignex.klause.lowering

import kotlin.math.abs
import kotlin.math.roundToLong

// The power of ten carrying a row of decimal-written values onto `Long` coefficients. Every written decimal
// has a power-of-ten denominator, so an exact multiplier restates the row rather than approximating it.
internal sealed interface RowScale {

    val multiplier: Long

    fun scale(value: Double): Long = (value * multiplier.toDouble()).roundToLong()

    class Exact(override val multiplier: Long) : RowScale

    // No power of ten makes the row whole within the recoverable range: a field printed at full double
    // precision (`-0.0214054799999994` for `-0.02140548`) has no source decimal to recover, so values round.
    class Rounded(override val multiplier: Long) : RowScale

    // The multiplier keeping the largest term recoverable sends the smallest to zero, which would drop it
    // from an integer row.
    class Unrepresentable(override val multiplier: Long) : RowScale
}

// A row is scaled by a single factor to stay equivalent, so the scale resolves once over the whole row.
internal class RowScaleBuilder {

    private var places = 0
    private var largest = 0.0
    private var smallestNonZero = Double.MAX_VALUE
    private var recoverable = true

    fun observe(value: Double) {
        if (!isFiniteValue(value)) return
        val magnitude = abs(value)
        if (magnitude > largest) largest = magnitude
        if (magnitude != 0.0 && magnitude < smallestNonZero) smallestNonZero = magnitude
        val needed = decimalPlacesOf(value)
        if (needed < 0) {
            recoverable = false
        } else if (needed > places) {
            places = needed
        }
    }

    fun resolve(): RowScale {
        if (largest == 0.0) return RowScale.Exact(1L)
        if (recoverable) {
            val exact = POWERS_OF_TEN[places]
            if (largest * exact.toDouble() <= MAX_UNITS) return RowScale.Exact(exact)
        }
        val fitted = largestFittingPower(largest)
        if (smallestNonZero * fitted.toDouble() < 0.5) return RowScale.Unrepresentable(fitted)
        return RowScale.Rounded(fitted)
    }
}

// `-1` when no decimal within MAX_PLACES places round-trips exactly.
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

private fun largestFittingPower(largest: Double): Long {
    var places = 0
    while (places < MAX_PLACES && largest * POWERS_OF_TEN[places + 1].toDouble() <= MAX_UNITS) places++
    return POWERS_OF_TEN[places]
}

private fun isFiniteValue(value: Double): Boolean = value - value == 0.0

// `value * 10^places` is computed in double; below this bound the accumulated rounding stays under half a
// unit, so `roundToLong` recovers the whole number the text names.
private const val MAX_UNITS = (1L shl 51).toDouble()

// Past this a field carries no recoverable source decimal, only double noise.
private const val MAX_PLACES = 15

private val POWERS_OF_TEN = LongArray(MAX_PLACES + 2).also {
    var power = 1L
    for (i in it.indices) {
        it[i] = power
        if (i < it.size - 1) power *= 10L
    }
}
