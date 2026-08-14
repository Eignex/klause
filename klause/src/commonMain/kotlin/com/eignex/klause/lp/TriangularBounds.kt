package com.eignex.klause.lp

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Per-variable bounds derived from a lower-triangular system by forward substitution.
 *
 * This is what the Hermite transformation is *for*. On a double-bounded system `lo ≤ Hy ≤ hi` whose `H`
 * is lower triangular, row `i`'s pivot column is the last one it mentions, so that row bounds its pivot
 * variable once the earlier ones are bounded. Sweeping rows top to bottom therefore bounds every pivot
 * variable in turn, and a variable bounded this way needs no invented search box — the bound is the
 * model's own.
 *
 * A column that pivots in no row is unconstrained by the triangle and stays open; the caller either drops
 * it (it appears in no row, so it takes any value) or keeps it in the open lane. Returning `null` for such
 * a column is the honest answer, not a failure.
 */
internal class TriangularBounds(
    /** Lower bound per column, `null` where the sweep derives none. */
    val lo: Array<BigInteger?>,
    /** Upper bound per column, `null` where the sweep derives none. */
    val hi: Array<BigInteger?>,
)

/**
 * Forward-substitute [h] (row-major, lower triangular) against row ranges `[rowLo, rowHi]` to bound each
 * column. A `null` row side is an unbounded direction and simply contributes nothing.
 *
 * Row `i` reads `rowLo[i] ≤ Σⱼ h[i][j]·yⱼ ≤ rowHi[i]`. Isolating the pivot column `p` gives
 * `h[i][p]·y_p ∈ [rowLo[i] − maxRest, rowHi[i] − minRest]`, where the rest-interval comes from the
 * already-bounded earlier columns. The division rounds inward — floor on the upper side, ceil on the
 * lower — which is exact over the integers, and the two swap when the pivot coefficient is negative.
 * A rest-term whose own side is unbounded makes that direction unbounded, so the pivot keeps only the
 * side that survives.
 */
@Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
internal fun triangularBounds(
    h: Array<Array<BigInteger>>,
    rowLo: Array<BigInteger?>,
    rowHi: Array<BigInteger?>,
): TriangularBounds {
    val cols = if (h.isEmpty()) 0 else h[0].size
    val lo = arrayOfNulls<BigInteger>(cols)
    val hi = arrayOfNulls<BigInteger>(cols)
    for (i in h.indices) {
        val pivot = pivotColumn(h[i]) ?: continue
        if (lo[pivot] != null || hi[pivot] != null) continue // a later row must not overwrite the bound

        // The rest-interval of the columns before the pivot; either side goes null once a term is open.
        var restLo: BigInteger? = BigInteger.ZERO
        var restHi: BigInteger? = BigInteger.ZERO
        for (j in 0 until pivot) {
            val a = h[i][j]
            if (a.isZero()) continue
            val termLo = if (a > BigInteger.ZERO) lo[j]?.times(a) else hi[j]?.times(a)
            val termHi = if (a > BigInteger.ZERO) hi[j]?.times(a) else lo[j]?.times(a)
            restLo = if (termLo == null || restLo == null) null else restLo + termLo
            restHi = if (termHi == null || restHi == null) null else restHi + termHi
        }

        // h[i][pivot]·y ∈ [rowLo − restHi, rowHi − restLo]
        val lowSide = rowLo[i]
        val highSide = rowHi[i]
        val prodLo = if (lowSide == null || restHi == null) null else lowSide - restHi
        val prodHi = if (highSide == null || restLo == null) null else highSide - restLo
        val c = h[i][pivot]
        if (c > BigInteger.ZERO) {
            lo[pivot] = prodLo?.let { ceilDiv(it, c) }
            hi[pivot] = prodHi?.let { floorDiv(it, c) }
        } else {
            // Dividing by a negative coefficient exchanges the two sides.
            lo[pivot] = prodHi?.let { ceilDiv(it, c) }
            hi[pivot] = prodLo?.let { floorDiv(it, c) }
        }
    }
    return TriangularBounds(lo, hi)
}

/** The last column row [row] mentions — its pivot in a lower-triangular form — or null for a zero row. */
private fun pivotColumn(row: Array<BigInteger>): Int? {
    for (j in row.indices.reversed()) if (!row[j].isZero()) return j
    return null
}

/** `⌊a / b⌋`; the bignum division truncates toward zero, so a negative exact quotient adjusts down. */
private fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
    val q = a / b
    val r = a - q * b
    return if (!r.isZero() && (r < BigInteger.ZERO) != (b < BigInteger.ZERO)) q - BigInteger.ONE else q
}

/** `⌈a / b⌉`; a positive exact quotient with a remainder adjusts up. */
private fun ceilDiv(a: BigInteger, b: BigInteger): BigInteger {
    val q = a / b
    val r = a - q * b
    return if (!r.isZero() && (r < BigInteger.ZERO) == (b < BigInteger.ZERO)) q + BigInteger.ONE else q
}
