package com.eignex.klause.lp.relaxation

import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength

// Match exact LP scalar admission without constructing enormous powers on the way to that boundary.
private fun envelopeProduct(left: BigFraction, right: BigFraction): BigFraction? {
    if (left.num.magnitudeBitLength().toLong() + right.num.magnitudeBitLength() > 4096 ||
        left.den.magnitudeBitLength().toLong() + right.den.magnitudeBitLength() > 4096) return null
    return left * right
}

internal data class EnvelopeInterval(val lower: BigFraction? = null, val upper: BigFraction? = null) {
    fun product(other: EnvelopeInterval): EnvelopeInterval {
        val lo = lower ?: return EnvelopeInterval()
        val hi = upper ?: return EnvelopeInterval()
        val otherLo = other.lower ?: return EnvelopeInterval()
        val otherHi = other.upper ?: return EnvelopeInterval()
        val endpoints = listOf(envelopeProduct(lo, otherLo), envelopeProduct(lo, otherHi),
            envelopeProduct(hi, otherLo), envelopeProduct(hi, otherHi))
        if (endpoints.any { it == null }) return EnvelopeInterval()
        val values = endpoints.filterNotNull()
        return EnvelopeInterval(values.reduce { a, b -> if (a < b) a else b },
            values.reduce { a, b -> if (a > b) a else b })
    }

    fun square(): EnvelopeInterval {
        val lo = lower
        val hi = upper
        val minimum = when {
            lo != null && lo > BigFraction.ZERO -> envelopeProduct(lo, lo) ?: BigFraction.ZERO
            hi != null && hi < BigFraction.ZERO -> envelopeProduct(hi, hi) ?: BigFraction.ZERO
            else -> BigFraction.ZERO
        }
        val loSquare = lo?.let { envelopeProduct(it, it) }
        val hiSquare = hi?.let { envelopeProduct(it, it) }
        val maximum = if (loSquare != null && hiSquare != null) {
            if (loSquare > hiSquare) loSquare else hiSquare
        } else null
        return EnvelopeInterval(minimum, maximum)
    }
}

// All rows have the form a*x <= b. Repeated columns are merged before they enter exact LP authority.
internal fun envelopeRow(
    terms: List<Pair<Int, BigFraction>>,
    rhs: BigFraction,
    strict: Boolean = false,
): ExactRationalInequality {
    val merged = HashMap<Int, BigFraction>()
    for ((column, coefficient) in terms) merged[column] = (merged[column] ?: BigFraction.ZERO) + coefficient
    val nonzero = merged.entries.filter { !it.value.isZero }.sortedBy { it.key }
    return ExactRationalInequality(nonzero.map { it.key }.toIntArray(), nonzero.map { it.value }, rhs, strict)
}

internal fun squareTangent(operand: Int, result: Int, at: BigFraction): ExactRationalInequality =
    envelopeRow(listOf(operand to (at + at), result to BigFraction.MINUS_ONE), at * at)

internal fun squareEnvelopes(operand: Int, result: Int, interval: EnvelopeInterval): List<ExactRationalInequality> {
    val rows = ArrayList<ExactRationalInequality>()
    rows += squareTangent(operand, result, BigFraction.ZERO)
    interval.lower?.let { if (!it.isZero) rows += squareTangent(operand, result, it) }
    interval.upper?.let {
        if (!it.isZero && it != interval.lower) rows += squareTangent(operand, result, it)
    }
    val lo = interval.lower
    val hi = interval.upper
    if (lo != null && hi != null) {
        rows += envelopeRow(listOf(result to BigFraction.ONE, operand to (lo + hi).negated()), (lo * hi).negated())
    }
    return rows
}

// McCormick's four supporting planes over a source-proved rectangle.
internal fun productEnvelopes(
    left: Int,
    right: Int,
    result: Int,
    leftInterval: EnvelopeInterval,
    rightInterval: EnvelopeInterval,
): List<ExactRationalInequality> {
    val fixedLeft = leftInterval.lower?.takeIf { it == leftInterval.upper }
    val fixedRight = rightInterval.lower?.takeIf { it == rightInterval.upper }
    if (fixedLeft != null || fixedRight != null) {
        val operand = if (fixedLeft != null) right else left
        val value = fixedLeft ?: checkNotNull(fixedRight)
        val terms = listOf(result to BigFraction.ONE, operand to value.negated())
        return listOf(envelopeRow(terms, BigFraction.ZERO),
            envelopeRow(terms.map { it.first to it.second.negated() }, BigFraction.ZERO))
    }
    val lo = leftInterval.lower ?: return emptyList()
    val hi = leftInterval.upper ?: return emptyList()
    val otherLo = rightInterval.lower ?: return emptyList()
    val otherHi = rightInterval.upper ?: return emptyList()
    fun lower(a: BigFraction, b: BigFraction) =
        envelopeRow(listOf(left to b, right to a, result to BigFraction.MINUS_ONE), a * b)
    fun upper(a: BigFraction, b: BigFraction) =
        envelopeRow(listOf(left to b.negated(), right to a.negated(), result to BigFraction.ONE), (a * b).negated())
    return listOf(lower(lo, otherLo), lower(hi, otherHi), upper(hi, otherLo), upper(lo, otherHi))
}
