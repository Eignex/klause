package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.WideLinComb
import com.eignex.klause.lowering.toWide
import com.eignex.klause.lowering.wideIntColumns
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.isZero
import com.eignex.klause.util.minus
import com.eignex.klause.util.negate
import com.eignex.klause.util.plus
import com.eignex.klause.util.signum
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

/**
 * The operations that need a *variable* holding a value, for values past the 64-bit range.
 *
 * `abs`, `div`, `mod` and `ite` all introduce a fresh quantity and pin it to their operands. A folded
 * term can already carry arbitrary precision ([IntComb.Wide]), but a fresh quantity used to need a
 * 64-bit domain, so these four rejected any operand beyond `Long`. Here the fresh quantity is a
 * combination over digit columns instead — ordinary variables with ordinary `Long` domains, weighted
 * `2^(width·i)` — so the value it can take is unlimited while the kernel sees only what it always saw.
 */

private val LONG_MAX = bigIntOf(Long.MAX_VALUE)

/** The bounds of [v]'s current presolve domain; a side is null when the domain is open there. */
private fun Compiler.Builder.domainBounds(v: Int): Pair<Long?, Long?> = when (val d = intDomains[v]) {
    is PresolveDomain.Finite -> d.domain.min to d.domain.max
    is PresolveDomain.Open -> d.lo to d.hi
}

/**
 * An open side has no finite magnitude. The open theory pipeline carries the fresh quantity as an open
 * ordinary integer; only the finite CP pipeline needs a digit vector for a genuinely wide finite range.
 */
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

/** A bound on `|value|` of [t]. */
internal fun Compiler.Builder.intCombMagnitude(t: IntComb): BigInt? {
    val (lo, hi) = wideRange(t.toWide()) ?: return null
    val a = lo.abs()
    val b = hi.abs()
    return if (a > b) a else b
}

/**
 * A fresh quantity able to take every value in `[-magnitude, magnitude]`.
 *
 * Inside `Long` that is one ordinary variable. Past it, the quantity is `Σᵢ dᵢ·2^(width·i)` over fresh
 * digit columns, the leading one signed — one digit vector per value, so search never revisits a value
 * it has already ruled out.
 */
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

/** `|x|` as a fresh quantity `y` with `y ≥ x`, `y ≥ −x` and `y = x ∨ y = −x`, at arbitrary precision. */
internal fun Compiler.Builder.wideAbsTerm(x: WideLinComb): IntComb {
    val y = freshWideInt(intCombMagnitude(IntComb.Wide(x)))
    val xc = IntComb.Wide(x)
    val negX = IntComb.Wide(x.scaled(BIG_ONE.negate()))
    assertRelation(">=", y, xc)
    assertRelation(">=", y, negX)
    factors.add(Clause(intArrayOf(reifyRelation("=", y, xc), reifyRelation("=", y, negX))))
    return y
}

/**
 * Euclidean `div`/`mod` by a **constant** divisor at arbitrary precision: fresh `q` and `m` with
 * `a = d·q + m` and `0 ≤ m < |d|`. A non-constant divisor is genuinely non-linear, so rejected.
 */
internal fun Compiler.Builder.wideDivModTerm(a: IntComb, b: IntComb, quotient: Boolean): IntComb {
    val bw = b.toWide()
    if (bw.coeffs.isNotEmpty()) smtUnsupported("non-constant divisor in div/mod")
    val d = bw.constant
    if (d.isZero()) smtUnsupported("division by zero in div/mod")
    val absd = d.abs()
    val aMagnitude = intCombMagnitude(a)
    // |q| ≤ |a|/|d| + 1: the quotient of the widest value the dividend can reach, with a unit of slack
    // for the remainder's sign.
    val qMagnitude = aMagnitude?.div(absd)?.plus(BIG_ONE)
    val q = freshWideInt(qMagnitude)
    val m = freshWideInt(absd)
    assertRelation(">=", m, IntComb.Wide(WideLinComb(emptyMap(), BIG_ZERO)))
    assertRelation("<=", m, IntComb.Wide(WideLinComb(emptyMap(), absd - BIG_ONE)))
    assertRelation("=", a, IntComb.Wide(q.toWide().scaled(d).plus(m.toWide())))
    return if (quotient) q else m
}
