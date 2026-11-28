package com.eignex.klause.lp.lattice

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times

internal class DoubleBoundedRow(val coefficients: SparseIntRow, val lower: BigInt, val upper: BigInt) {
    init {
        require(lower <= upper) { "double-bounded row has crossed bounds: $lower > $upper" }
    }
}

// The column transformation of the double-bounded part in M. Bromberger, *A Reduction from Unbounded Linear Mixed
// Arithmetic Problems into Bounded Problems*, IJCAR 2018, https://doi.org/10.1007/978-3-319-94205-6_22. `x = V·y`
// preserves every integer point. Deciding which rows are bounded needs the whole system and belongs above this leaf.
internal class DoubleBoundedReduction(val rows: List<DoubleBoundedRow>, val transform: UnimodularTransform) {
    fun recover(y: Array<BigInt>): Array<BigInt> {
        val x = Array(transform.size) { BIG_ZERO }
        transform.forEachEntry { row, col, value -> x[row] += value * y[col] }
        return x
    }
}

// Column operations rewrite only the variables, so each row keeps its activity interval. Cancellation returns
// null, since a partial transform would not preserve the integer lattice.
internal fun doubleBoundedReduction(
    rows: List<DoubleBoundedRow>,
    cols: Int,
    cancellation: ExecutionControl = ExecutionControl.Never,
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
