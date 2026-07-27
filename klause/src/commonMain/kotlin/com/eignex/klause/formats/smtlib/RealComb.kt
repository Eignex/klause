package com.eignex.klause.formats.smtlib

import com.eignex.klause.lp.BigFraction

/**
 * An exact rational linear combination over integer and LP-only real variables — the folded form of
 * a real-sorted SMT term. Coefficients stay [BigFraction]s until a row is emitted, where the least
 * common denominator scales them to exact integers.
 */
internal class RealComb(
    val intCoeffs: Map<Int, BigFraction>,
    val realCoeffs: Map<Int, BigFraction>,
    val constant: BigFraction,
) {
    fun plus(other: RealComb): RealComb = RealComb(
        mergeCoeffs(intCoeffs, other.intCoeffs),
        mergeCoeffs(realCoeffs, other.realCoeffs),
        constant + other.constant,
    )

    fun scaled(k: BigFraction): RealComb = RealComb(
        intCoeffs.mapValues { it.value * k },
        realCoeffs.mapValues { it.value * k },
        constant * k,
    )

    val isConstant: Boolean get() = intCoeffs.isEmpty() && realCoeffs.isEmpty()

    private companion object {
        fun mergeCoeffs(a: Map<Int, BigFraction>, b: Map<Int, BigFraction>): Map<Int, BigFraction> {
            if (b.isEmpty()) return a
            val out = HashMap(a)
            for ((v, c) in b) {
                val merged = (out[v]?.plus(c)) ?: c
                if (merged.isZero) out.remove(v) else out[v] = merged
            }
            return out
        }
    }
}
