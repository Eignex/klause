package com.eignex.klause.simplex.basis

import com.eignex.koblas.DenseMatrix
import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.koblas
import kotlin.math.abs

// Dense elimination publishes the same owned triangular representation as sparse LU, so reach,
// updates, numerical quality checks and exact ordering hints retain their existing contracts.
internal class DenseBasisFactors(private val source: SparseMatrix) {
    private val n = source.rows
    private val matrix = DenseMatrix.zero(n)
    private val multipliers = DoubleArray(n)
    private val pivotRow = DoubleArray(n)
    private val rowOrder = IntArray(n)
    private var inputEntries = 0
    private var pivots = 0
    private var candidates = 0L
    private var updates = 0L
    private var visits = 0L

    fun build(columns: IntArray, policy: LuPivotPolicy): LuBuildResult {
        matrix.values.fill(0.0)
        visits = matrix.values.size.toLong() + n
        inputEntries = 0
        pivots = 0
        candidates = 0
        updates = 0
        for (i in 0 until n) rowOrder[i] = i
        for (j in 0 until n) {
            var finite = true
            source.forEachInColumn(columns[j]) { i, value ->
                inputEntries++
                if (!value.isFinite()) finite = false
                matrix[i, j] = value
            }
            if (!finite) return rejected(LuBuildRejection.NONFINITE_INPUT)
        }
        for (k in 0 until n) {
            var row = k
            for (i in k until n) {
                candidates++
                if (abs(matrix[i, k]) > abs(matrix[row, k])) row = i
            }
            val pivot = matrix[row, k]
            if (pivot == 0.0 || abs(pivot) < policy.absoluteTolerance) {
                return rejected(LuBuildRejection.NO_USABLE_PIVOT)
            }
            if (row != k) swapRows(row, k)
            if (!eliminate(k, pivot)) return rejected(LuBuildRejection.ARITHMETIC_BREAKDOWN)
            pivots++
        }
        val lower = triangular(lower = true)
        val upper = triangular(lower = false)
        val factors = LuFactors(
            columns.copyOf(), IntArray(n) { -1 }, SymbolicLu(rowOrder.copyOf(), IntArray(n) { it }),
            lower, upper, transpose(lower), transpose(upper),
        )
        visits += 4L * n
        val work = work(lower.nnz + upper.nnz)
        return LuBuildResult.Built(factors, work, report(work))
    }

    private fun swapRows(first: Int, second: Int) {
        val row = rowOrder[first]
        rowOrder[first] = rowOrder[second]
        rowOrder[second] = row
        for (j in 0 until n) {
            val value = matrix[first, j]
            matrix[first, j] = matrix[second, j]
            matrix[second, j] = value
        }
        visits += 2L * n + 2
    }

    private fun eliminate(k: Int, pivot: Double): Boolean {
        multipliers.fill(0.0)
        pivotRow.fill(0.0)
        visits += 2L * n
        for (i in k + 1 until n) {
            val before = matrix[i, k]
            val multiplier = before / pivot
            visits++
            if (!multiplier.isFinite() || (before != 0.0 && multiplier == 0.0)) return false
            matrix[i, k] = multiplier
            multipliers[i] = multiplier
        }
        for (j in k + 1 until n) {
            pivotRow[j] = matrix[k, j]
            visits++
        }
        // The BLAS reduction may fuse products. Check nonzero underflow before handing it the update.
        for (j in k + 1 until n) {
            for (i in k + 1 until n) {
                val product = multipliers[i] * pivotRow[j]
                visits++
                if (!product.isFinite() || (multipliers[i] != 0.0 && pivotRow[j] != 0.0 && product == 0.0)) {
                    return false
                }
            }
        }
        if (k + 1 == n) return true
        updates += n.toLong() * n
        koblas.ger(-1.0, multipliers, pivotRow, matrix)
        for (j in k + 1 until n) {
            for (i in k + 1 until n) {
                visits++
                if (!matrix[i, j].isFinite()) return false
            }
        }
        return true
    }

    private fun triangular(lower: Boolean): SparseMatrix {
        var entries = 0
        for (j in 0 until n) {
            for (i in if (lower) j + 1 until n else 0..j) {
                visits++
                if (matrix[i, j] != 0.0) entries++
            }
        }
        val pointers = IntArray(n + 1)
        val rows = IntArray(entries)
        val values = DoubleArray(entries)
        var at = 0
        for (j in 0 until n) {
            for (i in if (lower) j + 1 until n else 0..j) {
                visits++
                val value = matrix[i, j]
                if (value != 0.0) {
                    rows[at] = i
                    values[at++] = value
                }
            }
            pointers[j + 1] = at
        }
        return SparseMatrix.wrap(n, n, pointers, rows, values)
    }

    private fun transpose(source: SparseMatrix): SparseMatrix {
        val pointers = IntArray(n + 1)
        for (j in 0 until n) source.forEachInColumn(j) { i, _ -> pointers[i + 1]++ }
        for (i in 0 until n) pointers[i + 1] += pointers[i]
        val next = pointers.copyOf()
        val rows = IntArray(source.nnz)
        val values = DoubleArray(source.nnz)
        for (j in 0 until n) {
            source.forEachInColumn(j) { i, value ->
                val at = next[i]++
                rows[at] = j
                values[at] = value
            }
        }
        visits += 3L * n + 2L * source.nnz
        return SparseMatrix.wrap(n, n, pointers, rows, values)
    }

    private fun work(entries: Int = 0) = LuBuildWork(
        inputEntries, pivots, 0, n, candidates, 0, updates, 0, entries, visits,
    )

    private fun report(work: LuBuildWork) = LuBuildReport(false, false, false, null, null, work, dense = true)

    private fun rejected(reason: LuBuildRejection): LuBuildResult {
        val work = work()
        return LuBuildResult.Rejected(reason, work, report(work))
    }
}
