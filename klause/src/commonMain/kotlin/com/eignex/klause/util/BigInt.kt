package com.eignex.klause.util

/**
 * Arbitrary-precision signed integer. Each platform maps it onto its fastest native representation,
 * so the whole API lives in top-level functions whose contracts hold identically on every platform.
 *
 * Equality is by value and [toString] renders the plain decimal form (`-` sign, no leading zeros).
 *
 * Code compiled against a platform artifact sees the platform type, whose own members win over these
 * functions. They agree everywhere except `%` on native, where the member signs the remainder like the
 * quotient; import [rem] under another name there to get the dividend's sign.
 */
expect class BigInt

/** The value 0. */
expect val BIG_ZERO: BigInt

/** The value 1. */
expect val BIG_ONE: BigInt

/** The value 2. */
expect val BIG_TWO: BigInt

/** The value 10. */
expect val BIG_TEN: BigInt

/** The integer equal to [value]. */
expect fun bigIntOf(value: Long): BigInt

/** The integer equal to [value]. */
expect fun bigIntOf(value: Int): BigInt

/** The integer equal to the unsigned [value]. */
expect fun bigIntOf(value: ULong): BigInt

/**
 * Parses [text] as an optional `+` or `-` followed by one or more ASCII decimal digits.
 *
 * @throws NumberFormatException when [text] has any other form.
 */
expect fun parseBigInt(text: String): BigInt

/**
 * `floor(value)` as an integer.
 *
 * @throws IllegalArgumentException when [value] is not finite.
 */
expect fun floorBigInt(value: Double): BigInt

/** `this + other`. */
expect operator fun BigInt.plus(other: BigInt): BigInt

/** `this - other`. */
expect operator fun BigInt.minus(other: BigInt): BigInt

/** `this · other`. */
expect operator fun BigInt.times(other: BigInt): BigInt

/**
 * The quotient `this / other` truncated toward zero.
 *
 * @throws ArithmeticException when [other] is zero.
 */
expect operator fun BigInt.div(other: BigInt): BigInt

/**
 * The remainder of the truncating [div]: it has the sign of `this` and `this == (this / other) · other + rem`.
 *
 * @throws ArithmeticException when [other] is zero.
 */
expect operator fun BigInt.rem(other: BigInt): BigInt

/** `-this`. */
expect operator fun BigInt.unaryMinus(): BigInt

/** Orders by numeric value. */
expect operator fun BigInt.compareTo(other: BigInt): Int

/** `-this`. */
expect fun BigInt.negate(): BigInt

/** `|this|`. */
expect fun BigInt.abs(): BigInt

/** -1, 0 or 1 as `this` is negative, zero or positive. */
expect fun BigInt.signum(): Int

/** Whether `this` is zero. */
expect fun BigInt.isZero(): Boolean

/** The non-negative greatest common divisor of `|this|` and `|other|`; `gcd(0, 0) = 0`. */
expect fun BigInt.gcd(other: BigInt): BigInt

/** `this · 2^places` for a non-negative [places]. */
expect infix fun BigInt.shl(places: Int): BigInt

/**
 * `|this| / 2^places` truncated, carrying the sign of `this`: the magnitude is shifted, so a negative
 * value rounds toward zero rather than toward negative infinity.
 */
expect infix fun BigInt.shr(places: Int): BigInt

/** The number of bits in `|this|`, without a sign bit; 0 for zero. */
expect fun BigInt.magnitudeBitLength(): Int

/** Whether `this` lies in `[Long.MIN_VALUE, Long.MAX_VALUE]`. */
expect fun BigInt.fitsLong(): Boolean

/** The low 64 bits of the two's-complement form of `this`, wrapping when it does not fit a [Long]. */
expect fun BigInt.toLong(): Long

/**
 * `this` as a [Long].
 *
 * @throws ArithmeticException when `this` does not fit a [Long].
 */
expect fun BigInt.toLongExact(): Long

/** The [Double] nearest to `this`, ties to even, and an infinity past the finite range. */
expect fun BigInt.toDouble(): Double

/** The greater of [a] and [b], or [a] when they are equal. */
fun maxOf(a: BigInt, b: BigInt): BigInt = if (a >= b) a else b

/** The lesser of [a] and [b], or [a] when they are equal. */
fun minOf(a: BigInt, b: BigInt): BigInt = if (a <= b) a else b

internal fun requireDecimalInteger(text: String) {
    val digitsStart = if (text.startsWith('+') || text.startsWith('-')) 1 else 0
    if (text.length == digitsStart || (digitsStart until text.length).any { text[it] !in '0'..'9' }) {
        throw NumberFormatException("Not a decimal integer: \"$text\"")
    }
}
