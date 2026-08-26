package com.eignex.klause.lp.lattice

import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Kannan and Bachem, *Polynomial Algorithms for Computing the Smith and Hermite Normal Forms of an
 * Integer Matrix* (SIAM J. Computing, 1979): the column Hermite form with its unimodular transform, so
 * the change of variables is a bijection of the integer lattice rather than merely of the rationals.
 *
 * A matrix in column Hermite normal form together with the column operations that produced it:
 * `H = A · V`, where [v] is unimodular (integer, determinant ±1) and therefore a bijection of the
 * integer lattice — so `Ax ⋛ b` and `Hy ⋛ b` have the same integer solutions, related by `x = Vy`.
 */
internal class HermiteForm(val h: List<SparseIntRow>, val v: UnimodularTransform)

/**
 * Column Hermite normal form of [a] over [cols] columns, with the unimodular column transform that
 * produces it, or null when [cancellation] fired before the reduction finished.
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
 *
 * A partial reduction is not usable: its `H` is not yet triangular, so forward substitution over it would
 * bound the wrong column. Cancellation therefore reports nothing rather than a prefix.
 */
@Suppress("NestedBlockDepth", "ReturnCount")
internal fun hermiteNormalForm(
    a: List<SparseIntRow>,
    cols: Int,
    cancellation: Cancellation = Cancellation.Never,
): HermiteForm? {
    val rows = a.size
    val h = SparseIntColumns(rows, cols, a)
    val v = UnimodularTransform(cols)

    var pivot = 0
    for (row in 0 until rows) {
        if (pivot >= cols) break
        // Clear the row to the right of the pivot column by repeated gcd steps: pick the column with the
        // smallest non-zero entry and subtract multiples of it from the others, which is the Euclidean
        // algorithm run across columns and terminates for the same reason.
        while (true) {
            if (cancellation()) return null
            var minCol = -1
            var minAbs = BigInteger.ZERO
            var nonZero = 0
            // The row view holds exactly the columns this row is non-zero in, so the search costs the
            // row's own support rather than the column count. Ties go to the lower column so that the
            // reduction does not depend on the hash order of the view.
            for (j in h.rowSupport[row]) {
                if (j < pivot) continue
                nonZero++
                val abs = h[row, j].abs()
                if (minCol < 0 || abs < minAbs || (abs == minAbs && j < minCol)) {
                    minAbs = abs
                    minCol = j
                }
            }
            if (nonZero <= 1) {
                if (minCol >= 0 && minCol != pivot) swapColumns(h, v, pivot, minCol)
                break
            }
            swapColumns(h, v, pivot, minCol)
            val p = h[row, pivot]
            for (j in ascendingSupport(h, row)) {
                if (j <= pivot) continue
                val e = h[row, j]
                if (e.isZero()) continue
                val q = e / p
                if (!q.isZero()) addMultipleOfColumn(h, v, target = j, source = pivot, factor = -q)
            }
        }
        if (h[row, pivot].isZero()) continue // the whole row is zero from `pivot` right; no pivot here
        if (h[row, pivot].signum() < 0) negateColumn(h, v, pivot)
        // Reduce the entries left of the pivot into `[0, pivot)`, the canonical form's uniqueness rule.
        val d = h[row, pivot]
        for (j in ascendingSupport(h, row)) {
            if (j >= pivot) continue
            var q = h[row, j] / d
            if (h[row, j] - q * d < BigInteger.ZERO) q -= BigInteger.ONE // floor, so the residue is >= 0
            if (!q.isZero()) addMultipleOfColumn(h, v, target = j, source = pivot, factor = -q)
        }
        pivot++
    }
    return HermiteForm(h.toRows(), v)
}

/** The row's non-zero columns in ascending order, snapshotted so the column operations may mutate it. */
private fun ascendingSupport(h: SparseIntColumns, row: Int): IntArray {
    val cs = h.rowSupport[row].toIntArray()
    cs.sort()
    return cs
}

private fun swapColumns(h: SparseIntColumns, v: UnimodularTransform, x: Int, y: Int) {
    h.swap(x, y)
    v.swap(x, y)
}

private fun negateColumn(h: SparseIntColumns, v: UnimodularTransform, col: Int) {
    h.negate(col)
    v.negate(col)
}

/** `column[target] += factor · column[source]`, applied to the matrix and to the transform alike. */
private fun addMultipleOfColumn(
    h: SparseIntColumns,
    v: UnimodularTransform,
    target: Int,
    source: Int,
    factor: BigInteger,
) {
    h.addMultiple(target, source, factor)
    v.addMultiple(target, source, factor)
}
