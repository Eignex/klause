package com.eignex.klause.util

import com.eignex.koblas.DimensionMismatch
import com.eignex.koblas.sparse.SparsePrimitives

// Validation and ordered diagnostics belong to the solver; arithmetic leaves use koblas primitives.
internal object SparseSlices {
    const val ARITHMETIC_NONFINITE: Int = 1

    const val ARITHMETIC_NONZERO_PRODUCT_UNDERFLOW: Int = 2

    @Suppress("LongParameterList")
    fun scatterAxpy(
        alpha: Double,
        indices: IntArray,
        indexOffset: Int,
        values: DoubleArray,
        valueOffset: Int,
        count: Int,
        accumulator: DoubleArray,
        marks: IntArray,
        epoch: Int,
        touched: IntArray,
        touchedOffset: Int,
        touchedCount: Int,
    ): Int {
        validateScatter(
            indices, indexOffset, values, valueOffset, count,
            accumulator, marks, epoch, touched, touchedOffset, touchedCount,
        )

        return SparsePrimitives.scatterWorkspace(
            alpha, indices, indexOffset, values, valueOffset, count,
            accumulator, marks, epoch, touched, touchedOffset, touchedCount,
        )
    }

    @Suppress("LongParameterList")
    fun scatterAxpyChecked(
        alpha: Double,
        indices: IntArray,
        indexOffset: Int,
        values: DoubleArray,
        valueOffset: Int,
        count: Int,
        accumulator: DoubleArray,
        marks: IntArray,
        epoch: Int,
        touched: IntArray,
        touchedOffset: Int,
        touchedCount: Int,
        arithmeticStatus: IntArray,
        statusOffset: Int,
    ): Int {
        requireWindow(arithmeticStatus.size, statusOffset, 1, "arithmetic status")
        requireDistinct(arithmeticStatus, indices, "arithmetic status and indices")
        requireDistinct(arithmeticStatus, marks, "arithmetic status and marks")
        requireDistinct(arithmeticStatus, touched, "arithmetic status and touched")
        validateScatter(
            indices, indexOffset, values, valueOffset, count,
            accumulator, marks, epoch, touched, touchedOffset, touchedCount,
        )

        return SparsePrimitives.scatterWorkspaceChecked(
            alpha, indices, indexOffset, values, valueOffset, count,
            accumulator, marks, epoch, touched, touchedOffset, touchedCount,
            arithmeticStatus, statusOffset, ARITHMETIC_NONFINITE, ARITHMETIC_NONZERO_PRODUCT_UNDERFLOW,
        )
    }

    @Suppress("LongParameterList")
    fun gatherTouched(
        touched: IntArray,
        touchedOffset: Int,
        touchedCount: Int,
        accumulator: DoubleArray,
        outIndices: IntArray,
        outIndexOffset: Int,
        outValues: DoubleArray,
        outValueOffset: Int,
        compactExactZeros: Boolean = false,
    ): Int {
        validateGather(
            touched,
            touchedOffset,
            touchedCount,
            accumulator,
            outIndices,
            outIndexOffset,
            outValues,
            outValueOffset,
        )
        return SparsePrimitives.gatherWorkspace(
            touched, touchedOffset, touchedCount, accumulator,
            outIndices, outIndexOffset, outValues, outValueOffset,
            compactExactZeros, marks = null,
        )
    }

    @Suppress("LongParameterList")
    fun gatherClearTouched(
        touched: IntArray,
        touchedOffset: Int,
        touchedCount: Int,
        accumulator: DoubleArray,
        marks: IntArray,
        outIndices: IntArray,
        outIndexOffset: Int,
        outValues: DoubleArray,
        outValueOffset: Int,
        compactExactZeros: Boolean = false,
    ): Int {
        requireShape(accumulator.size == marks.size) {
            "accumulator and marks lengths differ: ${accumulator.size} vs ${marks.size}"
        }
        validateGather(
            touched,
            touchedOffset,
            touchedCount,
            accumulator,
            outIndices,
            outIndexOffset,
            outValues,
            outValueOffset,
        )
        requireDistinct(marks, touched, "marks and touched")
        requireDistinct(marks, outIndices, "marks and output indices")
        return SparsePrimitives.gatherWorkspace(
            touched, touchedOffset, touchedCount, accumulator,
            outIndices, outIndexOffset, outValues, outValueOffset,
            compactExactZeros, marks,
        )
    }

    fun clearTouched(touched: IntArray, touchedOffset: Int, touchedCount: Int, values: DoubleArray, marks: IntArray) {
        requireShape(values.size == marks.size) {
            "values and marks lengths differ: ${values.size} vs ${marks.size}"
        }
        requireWindow(touched.size, touchedOffset, touchedCount, "touched")
        requireDistinct(touched, marks, "touched and marks")
        validateIndices(touched, touchedOffset, touchedCount, values.size, "touched")

        SparsePrimitives.clearWorkspace(touched, touchedOffset, touchedCount, values, marks)
    }

    // Ordered multiply then add/subtract preserves intermediate diagnostics; a BLAS dot may fuse or reassociate.
    @Suppress("LongParameterList")
    fun reduceDotChecked(
        initial: Double,
        subtractProducts: Boolean,
        indices: IntArray,
        indexOffset: Int,
        values: DoubleArray,
        valueOffset: Int,
        count: Int,
        dense: DoubleArray,
        arithmeticStatus: IntArray,
        statusOffset: Int,
    ): Double {
        requireWindow(arithmeticStatus.size, statusOffset, 1, "arithmetic status")
        requireWindow(indices.size, indexOffset, count, "indices")
        requireWindow(values.size, valueOffset, count, "values")
        requireNonoverlap(
            arithmeticStatus,
            statusOffset,
            1,
            indices,
            indexOffset,
            count,
            "arithmetic status and indices",
        )
        validateIndices(indices, indexOffset, count, dense.size, "indices")

        var status = arithmeticStatus[statusOffset]
        var result = initial
        if (!initial.isFinite()) status = status or ARITHMETIC_NONFINITE
        for (k in 0 until count) {
            val left = values[valueOffset + k]
            val right = dense[indices[indexOffset + k]]
            val product = left * right
            val updated = if (subtractProducts) result - product else result + product
            if (!left.isFinite() || !right.isFinite() || !product.isFinite() || !updated.isFinite()) {
                status = status or ARITHMETIC_NONFINITE
            }
            if (left.isFinite() && right.isFinite() && left != 0.0 && right != 0.0 && product == 0.0) {
                status = status or ARITHMETIC_NONZERO_PRODUCT_UNDERFLOW
            }
            result = updated
        }
        arithmeticStatus[statusOffset] = status
        return result
    }

    fun activeColumnMaxAbs(
        rowIndices: IntArray,
        indexOffset: Int,
        values: DoubleArray,
        valueOffset: Int,
        count: Int,
        activeRows: BooleanArray,
    ): Double {
        requireWindow(rowIndices.size, indexOffset, count, "row indices")
        requireWindow(values.size, valueOffset, count, "values")
        validateIndices(rowIndices, indexOffset, count, activeRows.size, "row indices")
        return SparsePrimitives.activeMaximum(
            rowIndices,
            indexOffset,
            values,
            valueOffset,
            count,
            activeRows,
        )
    }

    @Suppress("LongParameterList")
    fun pivotCandidatePositions(
        rowIndices: IntArray,
        indexOffset: Int,
        values: DoubleArray,
        valueOffset: Int,
        count: Int,
        activeRows: BooleanArray,
        columnMaximum: Double,
        absoluteTolerance: Double,
        relativeThreshold: Double,
        outPositions: IntArray,
        outOffset: Int,
    ): Int {
        require(columnMaximum.isFinite() && columnMaximum >= 0.0) {
            "column maximum must be finite and nonnegative, got $columnMaximum"
        }
        require(absoluteTolerance.isFinite() && absoluteTolerance >= 0.0) {
            "absolute tolerance must be finite and nonnegative, got $absoluteTolerance"
        }
        require(relativeThreshold.isFinite() && relativeThreshold >= 0.0) {
            "relative threshold must be finite and nonnegative, got $relativeThreshold"
        }
        requireWindow(rowIndices.size, indexOffset, count, "row indices")
        requireWindow(values.size, valueOffset, count, "values")
        requireWindow(outPositions.size, outOffset, count, "candidate output")
        requireNonoverlap(rowIndices, indexOffset, count, outPositions, outOffset, count, "row indices and candidates")
        validateIndices(rowIndices, indexOffset, count, activeRows.size, "row indices")

        return SparsePrimitives.selectPivotCandidates(
            rowIndices, indexOffset, values, valueOffset, count, activeRows,
            absoluteTolerance, relativeThreshold * columnMaximum, outPositions, outOffset,
        )
    }
}

@Suppress("LongParameterList")
private fun validateScatter(
    indices: IntArray,
    indexOffset: Int,
    values: DoubleArray,
    valueOffset: Int,
    count: Int,
    accumulator: DoubleArray,
    marks: IntArray,
    epoch: Int,
    touched: IntArray,
    touchedOffset: Int,
    touchedCount: Int,
) {
    require(epoch != 0) { "scatter epoch must be nonzero" }
    requireShape(accumulator.size == marks.size) {
        "accumulator and marks lengths differ: ${accumulator.size} vs ${marks.size}"
    }
    requireWindow(indices.size, indexOffset, count, "indices")
    requireWindow(values.size, valueOffset, count, "values")
    requireWindow(touched.size, touchedOffset, touchedCount, "touched")
    requireDistinct(accumulator, values, "accumulator and values")
    requireDistinct(marks, indices, "marks and indices")
    requireDistinct(marks, touched, "marks and touched")
    validateIndices(indices, indexOffset, count, accumulator.size, "indices")

    var newTouches = 0
    for (k in 0 until count) {
        if (marks[indices[indexOffset + k]] != epoch) newTouches++
    }
    requireWindow(touched.size, touchedOffset + touchedCount, newTouches, "touched capacity")
    requireNonoverlap(
        indices,
        indexOffset,
        count,
        touched,
        touchedOffset,
        touchedCount + newTouches,
        "indices and touched",
    )
}

@Suppress("LongParameterList")
private fun validateGather(
    touched: IntArray,
    touchedOffset: Int,
    touchedCount: Int,
    accumulator: DoubleArray,
    outIndices: IntArray,
    outIndexOffset: Int,
    outValues: DoubleArray,
    outValueOffset: Int,
) {
    requireWindow(touched.size, touchedOffset, touchedCount, "touched")
    requireWindow(outIndices.size, outIndexOffset, touchedCount, "output indices")
    requireWindow(outValues.size, outValueOffset, touchedCount, "output values")
    requireDistinct(accumulator, outValues, "accumulator and output values")
    requireNonoverlap(
        touched,
        touchedOffset,
        touchedCount,
        outIndices,
        outIndexOffset,
        touchedCount,
        "touched and output indices",
    )
    validateIndices(touched, touchedOffset, touchedCount, accumulator.size, "touched")
}

private fun validateIndices(indices: IntArray, offset: Int, count: Int, dimension: Int, name: String) {
    for (k in 0 until count) {
        val index = indices[offset + k]
        requireIndex(index in 0 until dimension) { "$name entry $index is outside [0, $dimension)" }
    }
}

private fun requireWindow(length: Int, offset: Int, count: Int, name: String) {
    require(offset >= 0 && count >= 0 && offset.toLong() + count <= length) {
        "$name window [$offset, ${offset.toLong() + count}) exceeds length $length"
    }
}

private fun requireDistinct(first: Any, second: Any, name: String) {
    require(first !== second) { "$name must use distinct buffers" }
}

private fun requireNonoverlap(
    first: IntArray,
    firstOffset: Int,
    firstCount: Int,
    second: IntArray,
    secondOffset: Int,
    secondCount: Int,
    name: String,
) {
    require(first !== second || firstOffset + firstCount <= secondOffset || secondOffset + secondCount <= firstOffset) {
        "$name windows must not overlap"
    }
}

private inline fun requireShape(condition: Boolean, message: () -> String) {
    if (!condition) throw DimensionMismatch(message())
}

private inline fun requireIndex(condition: Boolean, message: () -> String) {
    if (!condition) throw IndexOutOfBoundsException(message())
}
