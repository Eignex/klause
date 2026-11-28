package com.eignex.klause.lp.lattice

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.isZero
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.signum
import com.eignex.util.numbers.times
import com.eignex.util.numbers.unaryMinus

// Column Hermite form `H = A · V` (R. Kannan and A. Bachem, *Polynomial Algorithms for Computing the Smith and
// Hermite Normal Forms of an Integer Matrix*, SIAM J. Comput. 8(4) (1979), https://doi.org/10.1137/0208040). [v] is
// unimodular, so `Ax ⋛ b` and `Hy ⋛ b` have the same integer solutions, related by `x = Vy`.
internal class HermiteForm(val h: List<SparseIntRow>, val v: UnimodularTransform)

// Lower triangular: every entry right of a row's pivot is zero, so each variable is bounded along its pivot row or
// has an all-zero column. Swaps, negations and integer column additions are unimodular, so [HermiteForm.v] is too.
// A partial `H` is not triangular and would bound the wrong column, so cancellation returns null.
@Suppress("NestedBlockDepth", "ReturnCount")
internal fun hermiteNormalForm(
    a: List<SparseIntRow>,
    cols: Int,
    cancellation: ExecutionControl = ExecutionControl.Never,
): HermiteForm? {
    val rows = a.size
    val h = SparseIntColumns(rows, cols, a)
    val v = UnimodularTransform(cols)

    var pivot = 0
    for (row in 0 until rows) {
        if (pivot >= cols) break
        // The Euclidean algorithm across columns clears the row right of the pivot.
        while (true) {
            if (cancellation()) return null
            cancellation.charge(LATTICE_WORK_WEIGHT * (1L + h.rowSupport[row].size))
            var minCol = -1
            var minAbs = BIG_ZERO
            var nonZero = 0
            // Ties go to the lower column so the reduction does not depend on the view's hash order.
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
        if (h[row, pivot].isZero()) continue
        if (h[row, pivot].signum() < 0) negateColumn(h, v, pivot)
        // Entries left of the pivot reduce into `[0, d)`, which makes the form unique.
        val d = h[row, pivot]
        for (j in ascendingSupport(h, row)) {
            if (j >= pivot) continue
            var q = h[row, j] / d
            if (h[row, j] - q * d < BIG_ZERO) q -= BIG_ONE
            if (!q.isZero()) addMultipleOfColumn(h, v, target = j, source = pivot, factor = -q)
        }
        pivot++
    }
    return HermiteForm(h.toRows(), v)
}

// Snapshotted so the column operations may mutate the row.
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

private fun addMultipleOfColumn(
    h: SparseIntColumns,
    v: UnimodularTransform,
    target: Int,
    source: Int,
    factor: BigInt,
) {
    h.addMultiple(target, source, factor)
    v.addMultiple(target, source, factor)
}
