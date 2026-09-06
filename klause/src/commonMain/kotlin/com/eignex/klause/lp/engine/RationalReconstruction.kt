package com.eignex.klause.lp.engine

import com.eignex.klause.util.Int128
import kotlin.math.abs
import kotlin.math.floor

/**
 * The simplest rational within [tolerance] of [value], with denominator at most [maxDenominator], or
 * null when none exists — the float is not a small rational in disguise, or the expansion overflowed.
 *
 * The entries of a simplex ray `ρ = eᵣᵀB⁻¹` are ratios of minors of `B`, so they are rationals whose
 * denominators divide `det B` — usually small integers, however large the basis. A float carrying such
 * a value to sixteen digits determines it uniquely once the denominator is bounded, which is what makes
 * recovery possible where rounding to a fixed scale is not: rounding produces the nearest multiple of
 * `2⁻ᵏ`, which is almost never the value itself, and a certificate needing an exact zero cannot use
 * "almost".
 *
 * Continued fractions give the best rational approximation for a given denominator bound, in increasing
 * order of denominator, so the first convergent inside [tolerance] is the simplest one — which is the
 * one most likely to be the value the float was rounded from. This is the reconstruction step of the
 * exact-LP literature (Applegate, Cook, Dash and Espinoza's QSopt_ex, and the iterative-refinement work
 * that followed), used here for the same reason: a float solve is cheap, and a float answer is usually
 * the exact answer in disguise.
 */
@Suppress("ReturnCount")
internal fun reconstructRational(
    value: Double,
    maxDenominator: Long = DEFAULT_MAX_DENOMINATOR,
    tolerance: Double = DEFAULT_TOLERANCE,
): Rational? {
    if (!value.isFinite()) return null
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
