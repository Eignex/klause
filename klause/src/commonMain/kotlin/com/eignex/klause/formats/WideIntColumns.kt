package com.eignex.klause.formats

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * A wide integer variable rewritten as digit columns.
 *
 * [columns] are the fresh variables standing for the digits, least significant first; each ranges over
 * `[0, 2^width)`. [negative] is the matching vector for the negative part — a signed quantity is carried
 * as the difference of two non-negative digit vectors, so every digit domain stays a plain
 * `[0, 2^width)` and the sign lives in the linear row rather than in the encoding.
 *
 * The value is `Σᵢ (posᵢ − negᵢ)·2^(width·i)`, which [weights] gives directly as the coefficients to use
 * when a row that mentioned the original variable is rewritten over its digits.
 */
internal class WideIntColumns(val columns: IntArray, val negative: IntArray, val width: Int) {
    /** `2^(width·i)` per digit position, the coefficient a rewritten row multiplies each digit by. */
    fun weights(): Array<BigInteger> = Array(columns.size) { WideIntDigits.pow2(width * it) }

    /** Upper bound of each digit's domain: `2^width − 1`. */
    fun digitMax(): Long = (WideIntDigits.pow2(width) - BigInteger.ONE).longValue()
}

/**
 * Allocate digit columns for a variable whose values reach [magnitude], using coefficients up to
 * [maxCoeff] in the rows it appears in. [fresh] supplies a new variable index per call.
 *
 * Returns null when [maxCoeff] leaves no usable digit width ([WideIntDigits.NO_ROOM]): the coefficient
 * itself is out of range there, so the row belongs to the wide-coefficient propagators rather than to
 * this decomposition.
 *
 * The width is chosen so that `maxCoeff · 2^width` stays inside `Long`. That is what keeps the ordinary
 * bound reasoning working on the rewritten rows — decomposing the variable alone is not enough, because
 * a propagator that overflows multiplying a coefficient by a domain bound stops deriving the bound and
 * the row silently loses its refutation strength while still admitting solutions.
 */
internal fun wideIntColumns(magnitude: BigInteger, maxCoeff: BigInteger, fresh: () -> Int): WideIntColumns? {
    val width = WideIntDigits.widthFor(maxCoeff)
    if (width == WideIntDigits.NO_ROOM) return null
    val count = WideIntDigits.digitCount(magnitude, width)
    val pos = IntArray(count) { fresh() }
    val neg = IntArray(count) { fresh() }
    return WideIntColumns(pos, neg, width)
}

/**
 * A linear term set rewritten over digit columns: `terms` pairs each column with its coefficient.
 *
 * A row that mentioned a decomposed variable with coefficient `c` gains, per digit position `i`, the
 * pair `(posᵢ, c·2^(width·i))` and `(negᵢ, −c·2^(width·i))` — the positive and negative vectors differ
 * only in sign, which is how a signed value is carried while every digit domain stays non-negative.
 *
 * Every emitted coefficient fits `Long` by construction: the width was chosen against the largest
 * coefficient the variable appears with, so no `coefficient × digit-bound` product can overflow. A
 * position whose coefficient would nonetheless escape `Long` yields null rather than a wrapped row.
 */
internal fun WideIntColumns.rewriteTerm(coeff: BigInteger): List<Pair<Int, Long>>? {
    val out = ArrayList<Pair<Int, Long>>(columns.size * 2)
    val limit = BigInteger.fromLong(Long.MAX_VALUE)
    val floor = BigInteger.fromLong(Long.MIN_VALUE)
    weights().forEachIndexed { i, w ->
        val c = coeff * w
        if (c > limit || c < floor) return null
        val asLong = c.longValue()
        if (asLong == 0L) return@forEachIndexed
        out.add(columns[i] to asLong)
        if (asLong == Long.MIN_VALUE) return null // its negation is not representable
        out.add(negative[i] to -asLong)
    }
    return out
}
