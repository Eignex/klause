package com.eignex.klause.lp.lattice

import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.times

/** One exact row whose activity is bounded on both sides. */
internal class DoubleBoundedRow(val coefficients: SparseIntRow, val lower: BigInt, val upper: BigInt) {
    init {
        require(lower <= upper) { "double-bounded row has crossed bounds: $lower > $upper" }
    }
}

/**
 * An exact, lattice-preserving rewrite of a double-bounded integer system.
 *
 * Bromberger's Double-Bounded Reduction separates an arbitrary system into an unbounded part and a
 * double-bounded part. This type owns the latter's column transformation: the caller retains the row
 * ranges, while `x = V·y` preserves every integer point exactly. It deliberately does not decide which
 * source rows are bounded; that requires the full active mixed-arithmetic system and belongs above this
 * leaf package.
 */
internal class DoubleBoundedReduction(val rows: List<DoubleBoundedRow>, val transform: UnimodularTransform) {
    /** Recover the source integer point `x = V·y`. */
    fun recover(y: Array<BigInt>): Array<BigInt> {
        val x = Array(transform.size) { BIG_ZERO }
        transform.forEachEntry { row, col, value -> x[row] += value * y[col] }
        return x
    }
}

/**
 * Apply a unimodular column transformation to a double-bounded system.
 *
 * Each returned row has the same closed activity interval as its input row, because column operations
 * rewrite only the variables. A cancellation returns null instead of a partially transformed system:
 * mixing transformed rows with an incomplete transform would not preserve the integer lattice.
 */
internal fun doubleBoundedReduction(
    rows: List<DoubleBoundedRow>,
    cols: Int,
    cancellation: Cancellation = Cancellation.Never,
): DoubleBoundedReduction? {
    if (cols == 0) return DoubleBoundedReduction(rows, UnimodularTransform(0))
    val hermite = hermiteNormalForm(rows.map(DoubleBoundedRow::coefficients), cols, cancellation) ?: return null
    return DoubleBoundedReduction(
        List(rows.size) { index ->
            DoubleBoundedRow(hermite.h[index], rows[index].lower, rows[index].upper)
        },
        hermite.v,
    )
}
