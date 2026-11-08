package com.eignex.klause.lp.engine

import com.eignex.koblas.SparseMatrix

// Matrix arrays are immutable inputs; factors and mutable solve vectors stay with each numerical owner.
internal class SimplexMatrixView(
    numerical: LpScalingView,
    val columns: SparseMatrix,
    val colPtr: IntArray,
    val rowIdx: IntArray,
    val colVal: DoubleArray,
    val rowCols: Array<IntArray>,
    val rowVals: Array<DoubleArray>,
) {
    private val sourcePointers = numerical.colPtr
    private val sourceRows = numerical.rowIdx
    private val sourceValues = numerical.colVal

    fun matches(numerical: LpScalingView): Boolean =
        numerical.colPtr === sourcePointers && numerical.rowIdx === sourceRows &&
            numerical.colVal === sourceValues && columns.rows == numerical.m && columns.cols == numerical.numVars
}
