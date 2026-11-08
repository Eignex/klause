package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix

// Captured coefficients are immutable; owners share these bytes but allocate their own numerical scratch.
internal class BasisSource(matrix: SparseMatrix) {
    val pointers = matrix.copyColumnPointers()
    val rows = matrix.copyRowIndices()
    val values = matrix.values.copyOf()
    val matrix = SparseMatrix.wrap(matrix.rows, matrix.cols, pointers, rows, values)
}
