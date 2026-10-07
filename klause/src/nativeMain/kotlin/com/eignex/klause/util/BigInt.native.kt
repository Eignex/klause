// The contracts are documented once, on the expect declarations.
@file:Suppress(
    "NOTHING_TO_INLINE",
    "EXTENSION_SHADOWED_BY_MEMBER",
    "UndocumentedPublicFunction",
    "UndocumentedPublicProperty",
)

package com.eignex.klause.util

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.floor

actual typealias BigInt = BigInteger

actual inline val BIG_ZERO: BigInt get() = BigInteger.ZERO

actual inline val BIG_ONE: BigInt get() = BigInteger.ONE

actual inline val BIG_TWO: BigInt get() = BigInteger.TWO

actual inline val BIG_TEN: BigInt get() = BigInteger.TEN

actual inline fun bigIntOf(value: Long): BigInt = BigInteger.fromLong(value)

actual inline fun bigIntOf(value: Int): BigInt = BigInteger.fromInt(value)

actual inline fun bigIntOf(value: ULong): BigInt = BigInteger.fromULong(value)

actual fun parseBigInt(text: String): BigInt {
    // ionspin also accepts a decimal point with a zero fraction; the shared contract does not.
    requireDecimalInteger(text)
    return BigInteger.parseString(text, 10)
}

private const val DOUBLE_SIGNIFICAND_BITS = 52
private const val DOUBLE_EXPONENT_MASK = 0x7ffL
private const val DOUBLE_EXPONENT_BIAS = 1075
private const val DOUBLE_SIGNIFICAND_MASK = (1L shl DOUBLE_SIGNIFICAND_BITS) - 1
private const val LONG_RANGE_LIMIT = 9.223372036854775808E18

actual fun floorBigInt(value: Double): BigInt {
    require(value.isFinite()) { "Cannot floor a non-finite value: $value" }
    val floored = floor(value)
    if (floored >= -LONG_RANGE_LIMIT && floored < LONG_RANGE_LIMIT) return BigInteger.fromLong(floored.toLong())
    // ionspin converts a Double through its shortest decimal string, which drops digits past 2^53;
    // a value this large is integral, so its significand shifted by its exponent is exact.
    val bits = floored.toRawBits()
    val exponent = ((bits shr DOUBLE_SIGNIFICAND_BITS) and DOUBLE_EXPONENT_MASK).toInt() - DOUBLE_EXPONENT_BIAS
    val significand = (bits and DOUBLE_SIGNIFICAND_MASK) or (1L shl DOUBLE_SIGNIFICAND_BITS)
    val magnitude = BigInteger.fromLong(significand).shl(exponent)
    return if (floored < 0.0) magnitude.negate() else magnitude
}

actual inline operator fun BigInt.plus(other: BigInt): BigInt = add(other)

actual inline operator fun BigInt.minus(other: BigInt): BigInt = subtract(other)

actual inline operator fun BigInt.times(other: BigInt): BigInt = multiply(other)

actual inline operator fun BigInt.div(other: BigInt): BigInt = divide(other)

// ionspin signs a remainder like the quotient, so a negative divisor flips it; the contract follows the dividend.
actual inline operator fun BigInt.rem(other: BigInt): BigInt {
    val remainder = remainder(other)
    return if (remainder.signum() != 0 && remainder.signum() != signum()) remainder.negate() else remainder
}

actual inline operator fun BigInt.unaryMinus(): BigInt = negate()

actual inline operator fun BigInt.compareTo(other: BigInt): Int = compare(other)

actual inline fun BigInt.negate(): BigInt = negate()

actual inline fun BigInt.abs(): BigInt = abs()

actual inline fun BigInt.signum(): Int = signum()

actual inline fun BigInt.isZero(): Boolean = isZero()

actual inline fun BigInt.gcd(other: BigInt): BigInt = gcd(other)

actual inline infix fun BigInt.shl(places: Int): BigInt = shl(places)

actual inline infix fun BigInt.shr(places: Int): BigInt = shr(places)

actual inline fun BigInt.magnitudeBitLength(): Int = bitLength()

private val LONG_MIN = BigInteger.fromLong(Long.MIN_VALUE)
private val LONG_MAX = BigInteger.fromLong(Long.MAX_VALUE)

actual fun BigInt.fitsLong(): Boolean = compare(LONG_MIN) >= 0 && compare(LONG_MAX) <= 0

actual inline fun BigInt.toLong(): Long = longValue(exactRequired = false)

actual inline fun BigInt.toLongExact(): Long = longValue(exactRequired = true)

actual inline fun BigInt.toDouble(): Double = doubleValue(exactRequired = false)
