package com.eignex.klause.lp

import com.eignex.klause.solver.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Bareiss, *Sylvester's Identity and Multistep Integer-Preserving Gaussian Elimination* (Mathematics of
 * Computation, 1968): fraction-free elimination, so an integer matrix stays integer throughout and the
 * intermediate entries stay bounded by the minors rather than growing without limit.
 *
 * A system reduced to row echelon form by fraction-free elimination, with the pivot column of each row.
 *
 * [rows] is the reduced matrix; [pivots] holds, per reduced row, the column its leading entry sits in,
 * ascending. A row that reduced to all zeros is dropped, so [rows] has exactly the system's rank.
 */
internal class BareissEchelon(
    val rows: Array<Array<BigInteger>>,
    val pivots: IntArray,
    /** The right-hand sides carried through the same row operations, when the caller supplied them.
     *  Index-aligned with [rows]; empty when the elimination was run on coefficients alone. */
    val rhs: Array<BigInteger> = emptyArray(),
    /** True when a row reduced to `0 = c` with `c` non-zero: the equalities alone are unsatisfiable.
     *  Only ever set when right-hand sides were supplied, since without them it cannot be seen. */
    val inconsistent: Boolean = false,
)

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
internal fun bareissEchelon(
    a: Array<Array<BigInteger>>,
    rhsIn: Array<BigInteger>? = null,
    cancellation: Cancellation = Cancellation.Never,
): BareissEchelon {
    val m = a.size
    val n = if (m == 0) 0 else a[0].size
    if (m == 0 || n == 0) return BareissEchelon(emptyArray(), IntArray(0))
    val w = Array(m) { i -> Array(n) { j -> a[i][j] } }
    // The right-hand side is just another column: it must take every swap and every elimination the
    // coefficients take, or the reduced rows end up paired with bounds that belong to other rows.
    val rhs = rhsIn?.copyOf()

    val pivots = ArrayList<Int>()
    var prev = BigInteger.ONE
    var r = 0
    for (c in 0 until n) {
        if (r >= m) break
        // Each pivot column costs O(m·n) big-integer multiply-divides, and the entries grow with the
        // minors, so on a large equality block the whole elimination runs far past any budget it was
        // given. Polled per column: the caller treats a short result as "no bound derived", which is the
        // same answer it gets when the structure implies nothing.
        if (cancellation()) return BareissEchelon(emptyArray(), IntArray(0))
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
            if (rhs != null) {
                val tb = rhs[sel]
                rhs[sel] = rhs[r]
                rhs[r] = tb
            }
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
            if (rhs != null) rhs[i] = (pivot * rhs[i] - factor * rhs[r]) / prev
        }
        prev = pivot
        pivots.add(c)
        r++
    }

    // Drop the rows that reduced away; what remains is the rank. A dropped row whose right-hand side did
    // not reduce with it says `0 = c` for a non-zero `c`, which refutes the equalities outright.
    val inconsistent = rhs != null && (r until m).any { !rhs[it].isZero() }
    val kept = Array(r) { i -> w[i] }
    val keptRhs = if (rhs == null) emptyArray() else Array(r) { i -> rhs[i] }
    return BareissEchelon(kept, pivots.toIntArray(), keptRhs, inconsistent)
}
