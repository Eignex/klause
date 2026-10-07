package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Int128
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.isZero
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem
import com.eignex.klause.util.shl
import com.eignex.klause.util.shr
import com.eignex.klause.util.signum
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus
import kotlin.math.abs
import kotlin.math.floor

// A tolerance selects a candidate convergent; only the caller's exact proof check accepts it.
@Suppress("ReturnCount")
internal fun reconstructRational(
    value: Double,
    maxDenominator: Long = DEFAULT_MAX_DENOMINATOR,
    tolerance: Double = DEFAULT_TOLERANCE,
): Rational? {
    if (!value.isFinite() || maxDenominator < 1L || !tolerance.isFinite() || tolerance < 0.0) return null
    if (abs(value) >= Long.MAX_VALUE.toDouble()) return null
    if (abs(value) < tolerance) return Rational(0L, 1L)
    // The recurrence is carried on the magnitude, so a negative value only signs the numerator.
    val negative = value < 0.0
    val magnitude = abs(value)

    // Convergents pₖ/qₖ from the recurrence pₖ = aₖ·pₖ₋₁ + pₖ₋₂, qₖ = aₖ·qₖ₋₁ + qₖ₋₂.
    var prevNum = 1L
    var prevDen = 0L
    var num = floor(magnitude).toLong()
    var den = 1L
    var rest = magnitude - floor(magnitude)

    repeat(MAX_TERMS) {
        if (abs(num.toDouble() / den.toDouble() - magnitude) <= tolerance) {
            return Rational(if (negative) -num else num, den)
        }
        if (rest <= 0.0) return null // the expansion terminated without reaching the tolerance
        val next = 1.0 / rest
        if (!next.isFinite()) return null
        val term = floor(next).toLong()
        rest = next - floor(next)

        // pₖ and qₖ both grow; either overflowing means the value is not a rational this small.
        val nextNum = mulAdd(term, num, prevNum) ?: return null
        val nextDen = mulAdd(term, den, prevDen) ?: return null
        if (nextDen > maxDenominator) return null
        prevNum = num
        prevDen = den
        num = nextNum
        den = nextDen
    }
    return null
}

/** `a·b + c` in 128 bits, or null when it does not land back in a `Long`. */
private fun mulAdd(a: Long, b: Long, c: Long): Long? {
    val acc = Int128()
    acc.addProduct(a, b)
    acc.addProduct(c, 1L)
    return if (acc.fitsLong()) acc.toLong() else null
}

/**
 * [values] recovered as one integer vector: each entry reconstructed as a rational, then all scaled by
 * the least common denominator so the result is exactly `L · values` for a positive integer `L`.
 *
 * A ray is only defined up to a positive scale, so clearing the denominators loses nothing — and it is
 * what lets the certificate be checked in integer arithmetic. Null when any entry is not a small
 * rational, or when the common denominator or a scaled entry escapes a `Long`.
 */
@Suppress("ReturnCount")
internal fun reconstructIntegerVector(
    values: DoubleArray,
    maxDenominator: Long = DEFAULT_MAX_DENOMINATOR,
    tolerance: Double = DEFAULT_TOLERANCE,
): LongArray? {
    val parts = arrayOfNulls<Rational>(values.size)
    var common = 1L
    for (i in values.indices) {
        val r = reconstructRational(values[i], maxDenominator, tolerance) ?: return null
        parts[i] = r
        common = lcm(common, r.denominator) ?: return null
        if (common > maxDenominator) return null
    }
    val out = LongArray(values.size)
    for (i in values.indices) {
        val r = parts[i] ?: return null
        val scaled = Int128()
        scaled.addProduct(r.numerator, common / r.denominator)
        if (!scaled.fitsLong()) return null
        out[i] = scaled.toLong()
    }
    return out
}

/** Least common multiple, or null on overflow. Both arguments are positive. */
private fun lcm(a: Long, b: Long): Long? {
    val g = gcd(a, b)
    val acc = Int128()
    acc.addProduct(a / g, b)
    return if (acc.fitsLong()) acc.toLong() else null
}

private fun gcd(a: Long, b: Long): Long {
    var x = a
    var y = b
    while (y != 0L) {
        val t = x % y
        x = y
        y = t
    }
    return if (x < 0L) -x else x
}

/**
 * Denominators above this are not plausibly the value a float was rounded from, and the scaled
 * numerators stop fitting a `Long` soon after. Well above the small determinants a sparse simplex
 * basis produces, and far below where the common denominator would overflow.
 */
private const val DEFAULT_MAX_DENOMINATOR = 1L shl 40

/** A float carries about sixteen digits; this leaves margin for the arithmetic that produced it. */
private const val DEFAULT_TOLERANCE = 1e-9

/** Enough terms for any denominator under the bound: the convergents grow at least as fast as the
 *  Fibonacci numbers, which pass 2⁴⁰ by the sixtieth. */
private const val MAX_TERMS = 64

internal fun reconstructionDenominator(
    violation: BigFraction,
    correction: BigFraction,
    meter: ReconstructionMeter,
): BigInt {
    require(violation.signum() > 0 && correction.signum() > 0)
    val product = meter.fraction(violation * correction)
    val square = meter.integer(product.den / product.num)
    if (square <= BIG_ONE) return RECONSTRUCTION_FLOOR
    var root = meter.integer(BIG_ONE shl ((square.magnitudeBitLength() + 1) / 2))
    while (true) {
        val next = meter.integer((root + square / root) shr 1)
        if (next >= root) return maxOf(RECONSTRUCTION_FLOOR, root)
        root = next
    }
}

internal fun nextReconstructionRound(round: Int): Int = (round.toLong() * 6L / 5L + 1L)
    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal val RECONSTRUCTION_FLOOR: BigInt = BIG_ONE shl 24

internal fun reconstructExactVector(
    values: List<BigFraction>,
    denominator: BigInt,
    meter: ReconstructionMeter,
): List<BigFraction>? {
    require(denominator.signum() > 0)
    meter.phase = ReconstructionPhase.VECTOR
    return try {
        reconstructLongVector(values, denominator, meter)
    } catch (_: ReconstructionOverflow) {
        meter.vectorRestarts++
        reconstructBigVector(values, denominator, meter)
    }
}

@Suppress("ThrowsCount")
private fun reconstructLongVector(
    values: List<BigFraction>,
    denominator: BigInt,
    meter: ReconstructionMeter,
): List<BigFraction>? {
    if (denominator.magnitudeBitLength() > 63) throw ReconstructionOverflow()
    val limit = denominator.toLongExact()
    meter.storage(values.size.toLong() * 16L)
    val parts = ArrayList<BigFraction>(values.size)
    var common = 1L
    for (value in values) {
        meter.fraction(value)
        if (value.num.magnitudeBitLength() > 63 || value.den.magnitudeBitLength() > 63) throw ReconstructionOverflow()
        var n = value.num.abs().toLongExact()
        var d = value.den.toLongExact()
        var p0 = 0L
        var p1 = 1L
        var q0 = 1L
        var q1 = 0L
        while (d != 0L) {
            meter.step()
            val a = n / d
            val p = mulAdd(a, p1, p0) ?: throw ReconstructionOverflow()
            val q = mulAdd(a, q1, q0) ?: throw ReconstructionOverflow()
            if (q > limit) break
            p0 = p1
            p1 = p
            q0 = q1
            q1 = q
            val rest = n % d
            n = d
            d = rest
        }
        common = lcm(common, q1) ?: throw ReconstructionOverflow()
        if (common > limit) return null
        parts += meter.fraction(
            BigFraction.of(bigIntOf(if (value.signum() < 0) -p1 else p1), bigIntOf(q1)),
        )
    }
    return parts.toList()
}

private fun reconstructBigVector(
    values: List<BigFraction>,
    denominator: BigInt,
    meter: ReconstructionMeter,
): List<BigFraction>? {
    meter.integer(denominator)
    meter.storage(values.size.toLong() * 16L)
    val parts = ArrayList<BigFraction>(values.size)
    var common = BIG_ONE
    for (value in values) {
        meter.fraction(value)
        var n = value.num.abs()
        var d = value.den
        var p0 = BIG_ZERO
        var p1 = BIG_ONE
        var q0 = BIG_ONE
        var q1 = BIG_ZERO
        while (!d.isZero()) {
            val a = meter.integer(n / d)
            val p = meter.integer(a * p1 + p0)
            val q = meter.integer(a * q1 + q0)
            if (q > denominator) break
            p0 = p1
            p1 = p
            q0 = q1
            q1 = q
            val rest = meter.integer(n % d)
            n = d
            d = rest
        }
        common = meter.integer(common / common.gcd(q1) * q1)
        if (common > denominator) return null
        parts += meter.fraction(BigFraction.of(if (value.signum() < 0) -p1 else p1, q1))
    }
    return parts.toList()
}
