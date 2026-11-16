package com.eignex.klause.arithmetic.transcendental

import com.eignex.klause.simplex.exact.BigFraction

internal data class TranscendentalRelaxation(
    val domain: RationalEnclosure,
    val lowerSlope: BigFraction,
    val lowerIntercept: BigFraction,
    val upperSlope: BigFraction,
    val upperIntercept: BigFraction,
)

internal fun TranscendentalArithmetic.expRelaxation(
    domain: RationalEnclosure,
    anchor: BigFraction,
    terms: Int,
): TranscendentalRelaxation {
    require(domain.contains(anchor) && !domain.isPoint)
    val atAnchor = exp(RationalEnclosure.point(anchor), terms)
    val left = exp(RationalEnclosure.point(domain.lower), terms)
    val right = exp(RationalEnclosure.point(domain.upper), terms)
    val slope = divide(add(right.upper, left.upper.negated()), add(domain.upper, domain.lower.negated()))
    val leastFactor = add(BigFraction.ONE, add(domain.lower, anchor.negated()))
    val correction = if (leastFactor.signum() < 0) {
        multiply(add(atAnchor.upper, atAnchor.lower.negated()), leastFactor)
    } else BigFraction.ZERO
    return TranscendentalRelaxation(
        domain, atAnchor.lower,
        add(multiply(atAnchor.lower, add(BigFraction.ONE, anchor.negated())), correction),
        slope, add(left.upper, multiply(slope, domain.lower).negated()),
    )
}

internal fun TranscendentalArithmetic.logRelaxation(
    domain: RationalEnclosure,
    anchor: BigFraction,
    terms: Int,
): TranscendentalRelaxation {
    require(domain.lower.signum() > 0 && domain.contains(anchor) && !domain.isPoint)
    val atAnchor = log(RationalEnclosure.point(anchor), terms)
    val left = log(RationalEnclosure.point(domain.lower), terms)
    val right = log(RationalEnclosure.point(domain.upper), terms)
    val slope = divide(add(right.lower, left.lower.negated()), add(domain.upper, domain.lower.negated()))
    return TranscendentalRelaxation(
        domain, slope, add(left.lower, multiply(slope, domain.lower).negated()),
        divide(BigFraction.ONE, anchor), add(atAnchor.upper, BigFraction.MINUS_ONE),
    )
}
