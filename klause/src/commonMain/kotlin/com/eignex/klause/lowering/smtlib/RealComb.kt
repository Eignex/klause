package com.eignex.klause.lowering.smtlib

import com.eignex.klause.formats.smtlib.*

import com.eignex.klause.simplex.exact.BigFraction

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

/**
 * Sum of [combs], with every element after the first negated when [negateTail] (the n-ary `-` fold).
 * One mutable accumulator instead of a per-operand map copy, so a wide sum costs linear work in its
 * operand count — the exact-rational twin of `sumIntCombs`.
 */
internal fun sumRealCombs(combs: List<RealComb>, negateTail: Boolean = false): RealComb {
    val ints = HashMap<Int, BigFraction>()
    val reals = HashMap<Int, BigFraction>()
    var constant = BigFraction.ZERO
    for (idx in combs.indices) {
        val c = combs[idx]
        val neg = negateTail && idx > 0
        accumulate(ints, c.intCoeffs, neg)
        accumulate(reals, c.realCoeffs, neg)
        constant += if (neg) c.constant * BigFraction.MINUS_ONE else c.constant
    }
    return RealComb(ints, reals, constant)
}

private fun accumulate(into: HashMap<Int, BigFraction>, from: Map<Int, BigFraction>, negate: Boolean) {
    for ((v, c) in from) {
        val add = if (negate) c * BigFraction.MINUS_ONE else c
        val merged = into[v]?.plus(add) ?: add
        if (merged.isZero) into.remove(v) else into[v] = merged
    }
}
