// The contracts are documented once, on the expect declarations.
@file:Suppress(
    "NOTHING_TO_INLINE",
    "EXTENSION_SHADOWED_BY_MEMBER",
    "UndocumentedPublicFunction",
    "UndocumentedPublicProperty",
)

package com.eignex.klause.util

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.floor

actual typealias BigInt = BigInteger

actual inline val BIG_ZERO: BigInt get() = BigInteger.ZERO

actual inline val BIG_ONE: BigInt get() = BigInteger.ONE

actual inline val BIG_TWO: BigInt get() = BigInteger.TWO

actual inline val BIG_TEN: BigInt get() = BigInteger.TEN

actual inline fun bigIntOf(value: Long): BigInt = BigInteger.valueOf(value)

actual inline fun bigIntOf(value: Int): BigInt = BigInteger.valueOf(value.toLong())

private val TWO_TO_64: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)

actual fun bigIntOf(value: ULong): BigInt {
    val bits = value.toLong()
    return if (bits >= 0L) BigInteger.valueOf(bits) else BigInteger.valueOf(bits).add(TWO_TO_64)
}

actual fun parseBigInt(text: String): BigInt {
    // java.math accepts any Unicode digit; the shared contract is ASCII only.
    requireDecimalInteger(text)
    return BigInteger(text)
}

actual fun floorBigInt(value: Double): BigInt {
    require(value.isFinite()) { "Cannot floor a non-finite value: $value" }
    return BigDecimal(floor(value)).toBigIntegerExact()
}

actual inline operator fun BigInt.plus(other: BigInt): BigInt = add(other)

actual inline operator fun BigInt.minus(other: BigInt): BigInt = subtract(other)

actual inline operator fun BigInt.times(other: BigInt): BigInt = multiply(other)

actual inline operator fun BigInt.div(other: BigInt): BigInt = divide(other)

actual inline operator fun BigInt.rem(other: BigInt): BigInt = remainder(other)

actual inline operator fun BigInt.unaryMinus(): BigInt = negate()

actual inline operator fun BigInt.compareTo(other: BigInt): Int = compareTo(other)

actual inline fun BigInt.negate(): BigInt = negate()

actual inline fun BigInt.abs(): BigInt = abs()

actual inline fun BigInt.signum(): Int = signum()

actual inline fun BigInt.isZero(): Boolean = signum() == 0

actual inline fun BigInt.gcd(other: BigInt): BigInt = gcd(other)

actual inline infix fun BigInt.shl(places: Int): BigInt = shiftLeft(places)

// shiftRight floors a negative value; the shared contract shifts the magnitude.
actual inline infix fun BigInt.shr(places: Int): BigInt =
    if (signum() < 0) negate().shiftRight(places).negate() else shiftRight(places)

// bitLength counts a negative value's two's-complement bits, one short of the magnitude's at -2^k.
actual inline fun BigInt.magnitudeBitLength(): Int {
    val bits = bitLength()
    return if (signum() < 0 && lowestSetBit == bits) bits + 1 else bits
}

actual inline fun BigInt.fitsLong(): Boolean = bitLength() < Long.SIZE_BITS

actual inline fun BigInt.toLong(): Long = toLong()

actual inline fun BigInt.toLongExact(): Long = longValueExact()

actual inline fun BigInt.toDouble(): Double = toDouble()
