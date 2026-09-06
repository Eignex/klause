package com.eignex.klause.lp.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The spread of a relaxation's structural coefficients — what a scaling decision is made on.
 *
 * HiGHS gates its own equilibration on exactly this: `scaleLp` skips scaling outright when every
 * `|a_ij|` already lies in `[0.2, 5]`, on the grounds that a matrix that narrow has nothing to gain.
 * So the question "would scaling help klause's relaxations" is answered by measuring the spread
 * before writing any scaling, which is what this is for.
 *
 * Only *structural* entries are measured. The logical columns [lpColumns] appends carry a single
 * `±1` each, so counting them would drag every ratio toward 1 and report a well-conditioned matrix
 * whatever the model looks like.
 *
 * [rowRatio] and [columnRatio] are the worst single row's and single column's `max/min` magnitude.
 * They matter separately from [matrixRatio]: a matrix can span decades overall while each row is
 * internally tight (nothing to equilibrate away, only a global rescale), and it can look narrow
 * overall while individual rows mix magnitudes (exactly what row scaling fixes). An empty row or
 * column contributes no ratio.
 */
internal class LpConditioning(
    /** Smallest `|a_ij|` over the structural entries; 0 when there are none. */
    val minValue: Double,
    /** Largest `|a_ij|` over the structural entries; 0 when there are none. */
    val maxValue: Double,
    /** Worst `max/min` magnitude within one row; 1 when no row holds two entries. */
    val rowRatio: Double,
    /** Worst `max/min` magnitude within one column; 1 when no column holds two entries. */
    val columnRatio: Double,
    /** Structural entries measured — the sample size behind the rest. */
    val entries: Int,
) {
    /** `maxValue / minValue`: how many decades the whole matrix spans. 1 when there are no entries. */
    val matrixRatio: Double get() = if (minValue > 0.0) maxValue / minValue else 1.0

    /**
     * Whether HiGHS would decline to scale this matrix — every magnitude already inside `[0.2, 5]`.
     *
     * The decisive reading. When this is true of klause's relaxations there is nothing for
     * equilibration to do and the scaling work closes; when it is false, [rowRatio] and
     * [columnRatio] say whether the spread is the kind row and column factors can absorb.
     */
    val withinHighsNoScalingWindow: Boolean
        get() = entries == 0 || (minValue >= NO_SCALING_MIN_VALUE && maxValue <= NO_SCALING_MAX_VALUE)

    internal companion object {
        /** HiGHS's `no_scaling_original_matrix_min_value` (highs/lp_data/HighsLpUtils.cpp). */
        const val NO_SCALING_MIN_VALUE: Double = 0.2

        /** HiGHS's `no_scaling_original_matrix_max_value`. */
        const val NO_SCALING_MAX_VALUE: Double = 5.0

        val EMPTY: LpConditioning = LpConditioning(0.0, 0.0, 1.0, 1.0, 0)
    }
}

/**
 * Measure [model]'s structural coefficient spread in one pass over the nonzeros.
 *
 * `O(nnz)` and allocating two `m`-length arrays, so it is a diagnostic a caller runs once per model
 * — at the root, where the matrix is established — rather than per node. The matrix is fixed for a
 * model's lifetime ([LpModel.rebind] keeps the same [LpModel.csc]), so a per-node reading would
 * report the same numbers every time.
 */
internal fun lpConditioning(model: LpModel): LpConditioning {
    val n = model.n
    val m = model.m
    if (n == 0 || m == 0) return LpConditioning.EMPTY

    var minValue = Double.MAX_VALUE
    var maxValue = 0.0
    var entries = 0
    // Per-row extremes, accumulated column-wise since that is the order the CSC reads in.
    val rowMin = DoubleArray(m) { Double.MAX_VALUE }
    val rowMax = DoubleArray(m)

    var columnRatio = 1.0
    for (j in 0 until n) {
        var colMin = Double.MAX_VALUE
        var colMax = 0.0
        model.forEachInColumnD(j) { row, value ->
            val magnitude = abs(value)
            // A structural zero is not an entry: it carries no magnitude to equilibrate and would
            // otherwise drive every ratio to infinity.
            if (magnitude > 0.0) {
                entries++
                minValue = min(minValue, magnitude)
                maxValue = max(maxValue, magnitude)
                colMin = min(colMin, magnitude)
                colMax = max(colMax, magnitude)
                rowMin[row] = min(rowMin[row], magnitude)
                rowMax[row] = max(rowMax[row], magnitude)
            }
        }
        if (colMax > 0.0) columnRatio = max(columnRatio, colMax / colMin)
    }
    if (entries == 0) return LpConditioning.EMPTY

    var rowRatio = 1.0
    for (i in 0 until m) if (rowMax[i] > 0.0) rowRatio = max(rowRatio, rowMax[i] / rowMin[i])

    return LpConditioning(minValue, maxValue, rowRatio, columnRatio, entries)
}
