package com.eignex.klause.formats.flatzinc

import com.eignex.klause.simplex.exact.BigFraction

internal data class AffineFloatImage(
    val terms: Map<Int, BigFraction>,
    val constant: BigFraction = BigFraction.ZERO,
) {
    fun scaled(scale: BigFraction): AffineFloatImage = AffineFloatImage(
        terms.mapValues { (_, coefficient) -> coefficient * scale }.filterValues { !it.isZero },
        constant * scale,
    )

    operator fun plus(other: AffineFloatImage): AffineFloatImage {
        val result = terms.toMutableMap()
        for ((variable, coefficient) in other.terms) {
            result[variable] = (result[variable] ?: BigFraction.ZERO) + coefficient
        }
        return AffineFloatImage(result.filterValues { !it.isZero }, constant + other.constant)
    }
}
