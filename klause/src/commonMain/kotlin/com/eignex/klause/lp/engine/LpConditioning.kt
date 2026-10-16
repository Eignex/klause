package com.eignex.klause.lp.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Structural coefficient magnitudes used by [LpScalingView]'s eligibility gate. Logical
 * columns are excluded: their unit entries would bias conditioning measurements toward one.
 *
 * [rowRatio] and [columnRatio] give the largest within-row and within-column spreads;
 * [matrixRatio] measures overall spread. Empty rows and columns contribute no ratio.
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
    /** Structural entries measured. */
    val entries: Int,
) {
    /** `maxValue / minValue`; 1 when there are no entries. */
    val matrixRatio: Double get() = if (minValue > 0.0) maxValue / minValue else 1.0

    /**
     * Whether every magnitude already lies inside `[0.2, 5]`, where equilibration declines to scale; otherwise
     * [rowRatio] and [columnRatio] describe the spread the row and column factors attempt to absorb.
     */
    val withinHighsNoScalingWindow: Boolean
        get() = entries == 0 || (minValue >= NO_SCALING_MIN_VALUE && maxValue <= NO_SCALING_MAX_VALUE)

    internal companion object {
        /** Lower edge of the no-scaling window. */
        const val NO_SCALING_MIN_VALUE: Double = 0.2

        /** Upper edge of the no-scaling window. */
        const val NO_SCALING_MAX_VALUE: Double = 5.0

        val EMPTY: LpConditioning = LpConditioning(0.0, 0.0, 1.0, 1.0, 0)
    }
}

/**
 * Measure [model]'s structural coefficient spread in one pass over the nonzeros.
 *
 * `O(nnz)` with two `m`-length arrays, so a caller runs it once per model rather than per node: the matrix is
 * fixed for a model's lifetime ([LpModel.rebind] keeps the same [LpModel.csc]).
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
