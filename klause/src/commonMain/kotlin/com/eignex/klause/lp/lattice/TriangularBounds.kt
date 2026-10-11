package com.eignex.klause.lp.lattice

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.isZero
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times

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
    val lo: Array<BigInt?>,
    /** Upper bound per column, `null` where the sweep derives none. */
    val hi: Array<BigInt?>,
)

/**
 * Forward-substitute [h] (lower triangular, one [SparseIntRow] per row, over [cols] columns) against row
 * ranges `[rowLo, rowHi]` to bound each column. A `null` row side is an unbounded direction and simply
 * contributes nothing.
 *
 * Row `i` reads `rowLo[i] ≤ Σⱼ h(i, j)·yⱼ ≤ rowHi[i]`. Isolating the pivot column `p` gives
 * `h(i, p)·y_p ∈ [rowLo[i] − maxRest, rowHi[i] − minRest]`, where the rest-interval comes from the
 * already-bounded earlier columns. The division rounds inward — floor on the upper side, ceil on the
 * lower — which is exact over the integers, and the two swap when the pivot coefficient is negative.
 * A rest-term whose own side is unbounded makes that direction unbounded, so the pivot keeps only the
 * side that survives.
 *
 * Only the row's non-zeros feed the rest-interval, so the sweep costs the triangle's own content rather
 * than `rows · cols` — a zero term contributes nothing to either side in any case.
 */
@Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
internal fun triangularBounds(
    h: List<SparseIntRow>,
    cols: Int,
    rowLo: Array<BigInt?>,
    rowHi: Array<BigInt?>,
): TriangularBounds {
    val lo = arrayOfNulls<BigInt>(cols)
    val hi = arrayOfNulls<BigInt>(cols)
    for (i in h.indices) {
        val row = h[i]
        val pivot = row.trail
        if (pivot < 0 || pivot >= cols) continue
        if (lo[pivot] != null || hi[pivot] != null) continue // a later row must not overwrite the bound

        // The rest-interval of the columns before the pivot; either side goes null once a term is open.
        var restLo: BigInt? = BIG_ZERO
        var restHi: BigInt? = BIG_ZERO
        for (k in row.index.indices) {
            val j = row.index[k]
            if (j >= pivot) break
            val a = row.value[k]
            val termLo = if (a > BIG_ZERO) lo[j]?.times(a) else hi[j]?.times(a)
            val termHi = if (a > BIG_ZERO) hi[j]?.times(a) else lo[j]?.times(a)
            restLo = if (termLo == null || restLo == null) null else restLo + termLo
            restHi = if (termHi == null || restHi == null) null else restHi + termHi
        }

        // h(i, pivot)·y ∈ [rowLo − restHi, rowHi − restLo]
        val lowSide = rowLo[i]
        val highSide = rowHi[i]
        val prodLo = if (lowSide == null || restHi == null) null else lowSide - restHi
        val prodHi = if (highSide == null || restLo == null) null else highSide - restLo
        val c = row.value[row.index.size - 1]
        if (c > BIG_ZERO) {
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

/** `⌊a / b⌋`; the bignum division truncates toward zero, so a negative exact quotient adjusts down. */
private fun floorDiv(a: BigInt, b: BigInt): BigInt {
    val q = a / b
    val r = a - q * b
    return if (!r.isZero() && (r < BIG_ZERO) != (b < BIG_ZERO)) q - BIG_ONE else q
}

/** `⌈a / b⌉`; a positive exact quotient with a remainder adjusts up. */
private fun ceilDiv(a: BigInt, b: BigInt): BigInt {
    val q = a / b
    val r = a - q * b
    return if (!r.isZero() && (r < BIG_ZERO) == (b < BIG_ZERO)) q + BIG_ONE else q
}
