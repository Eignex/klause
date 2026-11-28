package com.eignex.klause.formats.smtlib

import com.eignex.util.numbers.BigFraction

// The folded form of a real-sorted term. Coefficients stay exact until a row is emitted, where the least common
// denominator scales them to integers.
internal class RealComb(
    intCoeffs: Map<Int, BigFraction>,
    realCoeffs: Map<Int, BigFraction>,
    val constant: BigFraction,
) {
    val intCoeffs: Map<Int, BigFraction> = intCoeffs.toMap()
    val realCoeffs: Map<Int, BigFraction> = realCoeffs.toMap()

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

// Negates every element after the first when [negateTail] (n-ary `-`). One accumulator keeps a wide sum linear.
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
