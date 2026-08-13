package com.eignex.klause.lp

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * A matrix in column Hermite normal form together with the column operations that produced it:
 * `H = A · V`, where [v] is unimodular (integer, determinant ±1) and therefore a bijection of the
 * integer lattice — so `Ax ⋛ b` and `Hy ⋛ b` have the same integer solutions, related by `x = Vy`.
 *
 * [h] and [v] are row-major: `h[i][j]` is row `i`, column `j`.
 */
internal class HermiteForm(val h: Array<Array<BigInteger>>, val v: Array<Array<BigInteger>>)

/**
 * Column Hermite normal form of [a] (row-major, `rows × cols`), with the unimodular column transform
 * that produces it.
 *
 * The form is lower triangular: reading rows top to bottom, each pivot sits strictly to the right of
 * nothing — every entry to the right of a row's pivot is zero — which is what makes a variable's bound
 * derivable from the row its column pivots in. That is the property the reduction is after: on a
 * double-bounded system with this shape every variable is either bounded by propagation along its pivot
 * row or has an all-zero column and drops out entirely.
 *
 * Computed by integer column operations only — swap, negate, and add an integer multiple of one column to
 * another — each of which is unimodular, so their product [HermiteForm.v] is too. Entries are
 * [BigInteger] because the intermediate coefficients of an integer elimination grow well past `Long` even
 * when the input and the result both fit comfortably.
 */
internal fun hermiteNormalForm(a: Array<Array<BigInteger>>): HermiteForm {
    val rows = a.size
    val cols = if (rows == 0) 0 else a[0].size
    val h = Array(rows) { i -> Array(cols) { j -> a[i][j] } }
    val v = Array(cols) { i -> Array(cols) { j -> if (i == j) BigInteger.ONE else BigInteger.ZERO } }

    var pivot = 0
    for (row in 0 until rows) {
        if (pivot >= cols) break
        // Clear the row to the right of the pivot column by repeated gcd steps: pick the column with the
        // smallest non-zero entry and subtract multiples of it from the others, which is the Euclidean
        // algorithm run across columns and terminates for the same reason.
        while (true) {
            var minCol = -1
            var nonZero = 0
            for (j in pivot until cols) {
                if (h[row][j].isZero()) continue
                nonZero++
                if (minCol < 0 || h[row][j].abs() < h[row][minCol].abs()) minCol = j
            }
            if (nonZero <= 1) {
                if (minCol >= 0 && minCol != pivot) {
                    swapColumns(h, v, pivot, minCol)
                }
                break
            }
            swapColumns(h, v, pivot, minCol)
            for (j in pivot + 1 until cols) {
                if (h[row][j].isZero()) continue
                val q = h[row][j] / h[row][pivot]
                if (!q.isZero()) addMultipleOfColumn(h, v, target = j, source = pivot, factor = -q)
            }
        }
        if (h[row][pivot].isZero()) continue // the whole row is zero from `pivot` right; no pivot here
        if (h[row][pivot].signum() < 0) negateColumn(h, v, pivot)
        // Reduce the entries left of the pivot into `[0, pivot)`, the canonical form's uniqueness rule.
        for (j in 0 until pivot) {
            val d = h[row][pivot]
            if (d.isZero()) continue
            var q = h[row][j] / d
            if (h[row][j] - q * d < BigInteger.ZERO) q -= BigInteger.ONE // floor, so the residue is ≥ 0
            if (!q.isZero()) addMultipleOfColumn(h, v, target = j, source = pivot, factor = -q)
        }
        pivot++
    }
    return HermiteForm(h, v)
}

private fun swapColumns(h: Array<Array<BigInteger>>, v: Array<Array<BigInteger>>, x: Int, y: Int) {
    if (x == y) return
    for (row in h) {
        val t = row[x]
        row[x] = row[y]
        row[y] = t
    }
    for (row in v) {
        val t = row[x]
        row[x] = row[y]
        row[y] = t
    }
}

private fun negateColumn(h: Array<Array<BigInteger>>, v: Array<Array<BigInteger>>, col: Int) {
    for (row in h) row[col] = -row[col]
    for (row in v) row[col] = -row[col]
}

/** `column[target] += factor · column[source]`, applied to the matrix and to the transform alike. */
private fun addMultipleOfColumn(
    h: Array<Array<BigInteger>>,
    v: Array<Array<BigInteger>>,
    target: Int,
    source: Int,
    factor: BigInteger,
) {
    for (row in h) row[target] += factor * row[source]
    for (row in v) row[target] += factor * row[source]
}
