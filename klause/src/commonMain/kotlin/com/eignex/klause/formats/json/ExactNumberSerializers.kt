package com.eignex.klause.formats.json

import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.isZero
import com.eignex.klause.util.parseBigInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Exact values travel as decimal strings: a JSON number past 2^53 is not portable, and a rational has
// no number form at all. Integers are `-?digits`; rationals add an optional `/digits` denominator.
// Rejections are IllegalArgumentException so the path-aware decoder prefixes the field's location.
private val integerText = Regex("-?[0-9]+")
private val rationalText = Regex("(-?[0-9]+)(?:/([0-9]+))?")

private fun parseExactInteger(text: String): BigInt {
    require(integerText.matches(text)) { "expected a decimal integer string, got '$text'" }
    return parseBigInt(text)
}

private fun parseRationalParts(text: String): Pair<BigInt, BigInt> {
    val match = requireNotNull(rationalText.matchEntire(text)) {
        "expected a rational string such as '-3/4', got '$text'"
    }
    val denominator = match.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseBigInt) ?: BIG_ONE
    require(!denominator.isZero()) { "rational '$text' has a zero denominator" }
    return parseBigInt(match.groupValues[1]) to denominator
}

/** Arbitrary-width integer as a decimal string. */
internal object BigIntSerializer : KSerializer<BigInt> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.eignex.klause.formats.json.ExactInteger", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigInt) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): BigInt = parseExactInteger(decoder.decodeString())
}

/** Exact rational as `numerator` or `numerator/denominator`. */
internal object BigFractionSerializer : KSerializer<BigFraction> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.eignex.klause.formats.json.ExactRational", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigFraction) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): BigFraction =
        parseRationalParts(decoder.decodeString()).let { (num, den) -> BigFraction.of(num, den) }
}

/** Source-semantic rational, written like [BigFractionSerializer]. */
internal object SemanticRationalSerializer : KSerializer<SemanticRational> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.eignex.klause.formats.json.SemanticRational", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SemanticRational) = encoder.encodeString(
        if (value.denominator == BIG_ONE) "${value.numerator}" else "${value.numerator}/${value.denominator}",
    )

    override fun deserialize(decoder: Decoder): SemanticRational =
        parseRationalParts(decoder.decodeString()).let { (num, den) -> SemanticRational.of(num, den) }
}
