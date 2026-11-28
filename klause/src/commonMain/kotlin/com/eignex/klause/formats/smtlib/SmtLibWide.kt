package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.AbsAffineValue
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.WideLinComb
import com.eignex.klause.lowering.toWide
import com.eignex.klause.lowering.wideIntColumns
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.isZero
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.signum
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact

// Wide-valued auxiliaries for `abs`, `div`, `mod` and `ite`. A quantity past 64 bits is represented by
// digit variables with `Long` domains and weights `2^(width·i)`, preserving exact operand relations
// through IntComb.Wide.

private val LONG_MAX = bigIntOf(Long.MAX_VALUE)

private fun Compiler.Builder.domainBounds(v: Int): Pair<Long?, Long?> = when (val d = intDomains[v]) {
    is PresolveDomain.Finite -> d.domain.min to d.domain.max
    is PresolveDomain.Open -> d.lo to d.hi
}

// Null when a side is open: the open theory pipeline carries such a quantity as an ordinary open integer.
internal fun Compiler.Builder.wideRange(w: WideLinComb): Pair<BigInt, BigInt>? {
    var lo = w.constant
    var hi = w.constant
    for ((v, c) in w.coeffs) {
        if (c.isZero()) continue
        val (dLo, dHi) = domainBounds(v)
        val loBound = if (c.signum() > 0) dLo else dHi
        val hiBound = if (c.signum() > 0) dHi else dLo
        if (loBound == null || hiBound == null) return null
        lo += c * bigIntOf(loBound)
        hi += c * bigIntOf(hiBound)
    }
    return lo to hi
}

internal fun Compiler.Builder.intCombMagnitude(t: IntComb): BigInt? {
    val (lo, hi) = wideRange(t.toWide()) ?: return null
    val a = lo.abs()
    val b = hi.abs()
    return if (a > b) a else b
}

// Past `Long` the quantity is `Σᵢ dᵢ·2^(width·i)` over fresh digit columns, the leading one signed, with one
// digit vector per value so search never revisits a ruled-out value.
internal fun Compiler.Builder.freshWideInt(magnitude: BigInt?): IntComb {
    if (magnitude == null) return IntComb.Narrow(LinComb(mapOf(newInt(null, null) to 1), 0))
    val m = magnitude.abs()
    if (m <= LONG_MAX) {
        val bound = m.toLongExact()
        return IntComb.Narrow(LinComb(mapOf(newInt(-bound, bound) to 1), 0))
    }
    // Unit weight on the least significant digit, so the widest digits available are the right choice.
    val cols = wideIntColumns(m, BIG_ONE) { lo, hi -> newInt(lo, hi) }
        ?: smtUnsupported("no digit width for a value of magnitude $m")
    val w = cols.weights()
    return IntComb.Wide(WideLinComb(cols.columns.indices.associate { cols.columns[it] to w[it] }, BIG_ZERO))
}

internal fun Compiler.Builder.wideAbsTerm(x: WideLinComb): IntComb {
    val y = freshWideInt(intCombMagnitude(IntComb.Wide(x)))
    val output = y.toWide()
    factors.add(AffineAbs(AbsAffineValue(x.coeffs, x.constant),
        AbsAffineValue(output.coeffs, output.constant)))
    return y
}

internal fun Compiler.Builder.wideDivModTerm(a: IntComb, b: IntComb, quotient: Boolean): IntComb =
    retainedEuclideanDivMod(a, b, quotient)
