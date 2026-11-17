package com.eignex.klause.arithmetic.polynomial

import com.eignex.util.numbers.BigFraction
import kotlin.math.nextDown
import kotlin.math.nextUp

// How an isolating interval stands against binary64 round-to-nearest, ties to even.
internal sealed interface BinaryRounding {
    // Every point the interval can hold rounds to [value], so the root inside does too.
    class Determined(val value: Double) : BinaryRounding

    // A rounding bound, or zero, lies strictly inside the interval at [at]; [approximate] rounds the midpoint.
    class Split(val at: BigFraction, val approximate: Double) : BinaryRounding
}

// The rounding of a point interval's value, or of the root strictly inside a nonpoint one. A nonpoint interval is
// determined once it lies within the rounding cell of its midpoint's value; until then the split is that cell's bound
// inside it. Zero is split on first, since the root's sign picks between the two zeros.
internal fun roundToBinary64(interval: RationalInterval): BinaryRounding {
    val (lower, upper) = interval
    if (interval.isPoint) return BinaryRounding.Determined(nearestBinary64(lower))
    val midpoint = (lower + upper) * HALF
    val rounded = round(midpoint, midpoint.toDouble())
    val candidate = rounded.value
    if (candidate == 0.0 && lower.signum() < 0 && upper.signum() > 0) {
        return BinaryRounding.Split(BigFraction.ZERO, candidate)
    }
    val below = rounded.cell.lower
    val above = rounded.cell.upper
    val split = below?.takeIf { lower < it && it < upper } ?: above?.takeIf { lower < it && it < upper }
    if (split != null) return BinaryRounding.Split(split, candidate)
    if ((below == null || below <= lower) && (above == null || upper <= above)) {
        return BinaryRounding.Determined(if (candidate == 0.0 && upper.signum() <= 0) -0.0 else candidate)
    }
    return BinaryRounding.Split(midpoint, candidate)
}

// [value] correctly rounded to binary64: the double whose exact rounding cell holds [value], with a tie going to the
// even significand and past the largest finite value to infinity. [seed] only starts the search, so a platform
// conversion that is off by a few units in the last place costs a few steps and not correctness.
internal fun nearestBinary64(value: BigFraction, seed: Double = value.toDouble()): Double = round(value, seed).value

private class Rounded(val value: Double, val cell: RoundingCell)

private fun round(value: BigFraction, seed: Double): Rounded {
    var candidate = if (seed.isNaN()) 0.0 else seed
    while (true) {
        val cell = roundingCell(candidate)
        val below = cell.lower
        val above = cell.upper
        val tie = when {
            below != null && value < below -> {
                candidate = candidate.nextDown()
                continue
            }
            above != null && value > above -> {
                candidate = candidate.nextUp()
                continue
            }
            value == below -> tieToEven(candidate, candidate.nextDown())
            value == above -> tieToEven(candidate, candidate.nextUp())
            else -> candidate
        }
        return Rounded(signed(tie, value), if (tie == candidate) cell else roundingCell(tie))
    }
}

private fun signed(rounded: Double, value: BigFraction): Double =
    if (rounded == 0.0) (if (value.signum() < 0) -0.0 else 0.0) else rounded

private fun tieToEven(first: Double, second: Double): Double = when {
    first.isInfinite() -> first
    second.isInfinite() -> second
    (first.toRawBits() and 1L) == 0L -> first
    else -> second
}

private class RoundingCell(val lower: BigFraction?, val upper: BigFraction?)

private val HALF = BigFraction.ofLong(2).reciprocal()

// The reals that round to [value] lie between these bounds, a bound itself going to whichever side is even. An
// infinity's cell is unbounded on its far side, and its near bound is where finite values overflow.
private fun roundingCell(value: Double): RoundingCell {
    fun exact(value: Double) = requireNotNull(BigFraction.ofDouble(value))
    if (value.isInfinite()) {
        val largest = exact(Double.MAX_VALUE)
        val overflow = largest + (largest - exact(Double.MAX_VALUE.nextDown())) * HALF
        return if (value > 0) RoundingCell(overflow, null) else RoundingCell(null, overflow.negated())
    }
    val point = exact(value)
    val below = value.nextDown()
    val above = value.nextUp()
    val lowerGap = if (below.isFinite()) point - exact(below) else exact(above) - point
    val upperGap = if (above.isFinite()) exact(above) - point else point - exact(below)
    return RoundingCell(point - lowerGap * HALF, point + upperGap * HALF)
}
