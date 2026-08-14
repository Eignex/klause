package com.eignex.klause.lp

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * A system reduced to row echelon form by fraction-free elimination, with the pivot column of each row.
 *
 * [rows] is the reduced matrix; [pivots] holds, per reduced row, the column its leading entry sits in,
 * ascending. A row that reduced to all zeros is dropped, so [rows] has exactly the system's rank.
 */
internal class BareissEchelon(val rows: Array<Array<BigInteger>>, val pivots: IntArray)

/**
 * Row echelon form of [a] (row-major) by Bareiss fraction-free elimination.
 *
 * Ordinary Gaussian elimination over the rationals would carry denominators; Bareiss divides each
 * update by the *previous* pivot, and that division is always exact because every intermediate is a
 * minor of the original matrix. So the reduction stays in exact integers with no gcd bookkeeping, and
 * entries stay bounded by Hadamard rather than growing like a naive fraction-free scheme.
 *
 * Row operations preserve the row space, so `Ax ⋛ b` and the reduced system have the same solutions when
 * the reduction is applied to the augmented matrix. This is the rational half of the mixed-echelon
 * transformation: the equalities collapse to echelon form here, and only the genuinely integral part is
 * left for the Hermite step, which is the expensive one.
 *
 * Zero rows are dropped rather than kept, so [BareissEchelon.rows] is exactly the rank — a system whose
 * equalities are dependent contributes only its independent ones.
 */
internal fun bareissEchelon(a: Array<Array<BigInteger>>): BareissEchelon {
    val m = a.size
    val n = if (m == 0) 0 else a[0].size
    if (m == 0 || n == 0) return BareissEchelon(emptyArray(), IntArray(0))
    val w = Array(m) { i -> Array(n) { j -> a[i][j] } }

    val pivots = ArrayList<Int>()
    var prev = BigInteger.ONE
    var r = 0
    for (c in 0 until n) {
        if (r >= m) break
        // A non-zero in this column at or below the current row makes it a pivot column.
        var sel = -1
        for (i in r until m) {
            if (!w[i][c].isZero()) {
                sel = i
                break
            }
        }
        if (sel < 0) continue
        if (sel != r) {
            val t = w[sel]
            w[sel] = w[r]
            w[r] = t
        }
        val pivot = w[r][c]
        for (i in r + 1 until m) {
            val factor = w[i][c]
            if (factor.isZero() && pivot.isZero()) continue
            for (j in c until n) {
                // The Bareiss update: (pivot*w[i][j] - factor*w[r][j]) / prev is exact — it is a minor of
                // the original matrix, so the division never leaves the integers.
                w[i][j] = (pivot * w[i][j] - factor * w[r][j]) / prev
            }
        }
        prev = pivot
        pivots.add(c)
        r++
    }

    // Drop the rows that reduced away; what remains is the rank.
    val kept = Array(r) { i -> w[i] }
    return BareissEchelon(kept, pivots.toIntArray())
}
