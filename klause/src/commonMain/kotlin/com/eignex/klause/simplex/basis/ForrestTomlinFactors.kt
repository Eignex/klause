package com.eignex.klause.simplex.basis

import com.eignex.klause.util.SparseSlices
import kotlin.math.abs

internal data class ForrestTomlinWork(
    val columnProducts: Long,
    val rowProducts: Long,
    val copiedEntries: Long,
    val upperEntries: Int,
    val transformEntries: Int,
)

// T L^-1 P B Q0 = V; V(order, order) is triangular. Labels are initial LU coordinates.
// Row transforms multiply on the left, so their transposes apply in reverse order in BTRAN.
internal class ForrestTomlinFactors(factors: LuFactors) {
    val upper = BasisTriangularMatrix(factors.upper)
    val transpose = BasisTriangularMatrix(factors.upperTranspose)
    private val transforms = mutableListOf<ForrestTomlinRow>()
    private val n = upper.columns.size
    private val column = BasisWorkspace(n)
    private val row = BasisWorkspace(n)
    private val multipliers = BasisWorkspace(n)
    private val initialEntries = factors.upper.nnz
    var upperEntries = initialEntries
        private set
    var transformEntries = 0
        private set
    val updateCount: Int get() = transforms.size
    var lastUpdateWork: ForrestTomlinWork? = null
        private set
    var lastSolveEntries = 0L
        private set

    fun forward(work: BasisWorkspace): Long {
        lastSolveEntries = 0
        for (i in transforms.indices) {
            val transform = transforms[i]
            try {
                transform.forward(work)
            } finally {
                lastSolveEntries = saturatedAdd(lastSolveEntries, transform.lastWork)
            }
        }
        return lastSolveEntries
    }

    fun backward(work: BasisWorkspace): Long {
        lastSolveEntries = 0
        for (i in transforms.size - 1 downTo 0) {
            val transform = transforms[i]
            try {
                transform.transpose(work)
            } finally {
                lastSolveEntries = saturatedAdd(lastSolveEntries, transform.lastWork)
            }
        }
        return lastSolveEntries
    }

    fun fillAdvice(factor: Double): Boolean = upperEntries.toDouble() + transformEntries > factor * initialEntries

    fun update(pivot: Int, spike: BasisWorkspace, tolerance: Double): Boolean {
        column.clear()
        row.clear()
        multipliers.clear()
        var columnProducts = 0L
        var rowProducts = 0L
        var copiedEntries = 0L
        var published = false
        lastUpdateWork = null
        try {
            for (k in 0 until spike.count) {
                val j = spike.indices[k]
                val value = spike.values[j]
                if (value == 0.0) continue
                column.scatter(value, upper.columns[j])
                columnProducts += upper.columns[j].count
            }
            val rows = transpose.columns.copyOf()
            for (i in 0 until n) {
                if (rows[i][pivot] != column.values[i]) {
                    rows[i] = replace(rows[i], pivot, column.values[i])
                    copiedEntries += rows[i].count
                }
            }
            row.load(rows[pivot])
            val order = upper.order
            val position = order.indexOf(pivot)
            for (k in position + 1 until n) {
                val j = order[k]
                val value = row.values[j]
                if (value == 0.0) continue
                val multiplier = basisQuotient(value, upper.columns[j][j])
                multipliers.set(j, multiplier)
                row.scatter(-multiplier, rows[j])
                rowProducts += rows[j].count
                // The eliminated entry is structural zero, independent of division roundoff.
                row.set(j, 0.0)
            }
            val diagonal = row.values[pivot]
            if (!diagonal.isFinite() || diagonal == 0.0 || abs(diagonal) < tolerance) return false
            basisQuotient(1.0, diagonal)
            rows[pivot] = BasisSlice(intArrayOf(pivot), doubleArrayOf(diagonal))
            copiedEntries++
            column.set(pivot, diagonal)
            val columns = upper.columns.copyOf()
            for (j in 0 until n) {
                if (j == pivot) {
                    columns[j] = compact(column)
                    copiedEntries += columns[j].count
                } else if (columns[j][pivot] != 0.0) {
                    columns[j] = replace(columns[j], pivot, 0.0)
                    copiedEntries += columns[j].count
                }
            }
            val nextOrder = order.copyOf()
            for (k in position until n - 1) nextOrder[k] = order[k + 1]
            nextOrder[n - 1] = pivot
            val transform = ForrestTomlinRow(pivot, compact(multipliers))
            val entries = columns.sumOf { it.count }
            val report = ForrestTomlinWork(
                columnProducts,
                rowProducts,
                copiedEntries,
                entries,
                transformEntries + transform.entries.count,
            )
            // Publish the two adjacency views and their shared order only after all checked arithmetic succeeds.
            transforms.add(transform)
            upper.columns = columns
            transpose.columns = rows
            upper.order = nextOrder
            transpose.order = nextOrder
            upperEntries = entries
            transformEntries = report.transformEntries
            lastUpdateWork = report
            published = true
            return true
        } catch (_: ArithmeticException) {
            return false
        } finally {
            if (!published) {
                lastUpdateWork = ForrestTomlinWork(
                    columnProducts,
                    rowProducts,
                    copiedEntries,
                    upperEntries,
                    transformEntries,
                )
            }
        }
    }

    private fun replace(slice: BasisSlice, index: Int, value: Double): BasisSlice {
        val previous = slice[index]
        val count = slice.count + (if (value == 0.0) 0 else 1) - (if (previous == 0.0) 0 else 1)
        val indices = IntArray(count)
        val values = DoubleArray(count)
        var out = 0
        var inserted = false
        for (k in slice.offset until slice.offset + slice.count) {
            val i = slice.indices[k]
            if (!inserted && i >= index) {
                if (value != 0.0) {
                    indices[out] = index
                    values[out++] = value
                }
                inserted = true
            }
            if (i != index) {
                indices[out] = i
                values[out++] = slice.values[k]
            }
        }
        if (!inserted && value != 0.0) {
            indices[out] = index
            values[out] = value
        }
        return BasisSlice(indices, values)
    }

    private fun compact(work: BasisWorkspace): BasisSlice {
        var count = 0
        for (k in 0 until work.count) {
            if (work.values[work.indices[k]] != 0.0) count++
        }
        val indices = IntArray(count)
        var out = 0
        for (k in 0 until work.count) {
            val i = work.indices[k]
            if (work.values[i] != 0.0) indices[out++] = i
        }
        indices.sort()
        return BasisSlice(indices, DoubleArray(count) { work.values[indices[it]] })
    }
}

internal class ForrestTomlinRow(val pivot: Int, val entries: BasisSlice) {
    private val arithmeticStatus = IntArray(1)
    var lastWork = 0L
        private set

    fun forward(work: BasisWorkspace): Long {
        lastWork = entries.count.toLong()
        arithmeticStatus[0] = 0
        val value = SparseSlices.reduceDotChecked(
            work.values[pivot], true,
            entries.indices, entries.offset, entries.values, entries.offset, entries.count,
            work.values, arithmeticStatus, 0,
        )
        if (arithmeticStatus[0] != 0) {
            throw BasisArithmeticException("checked basis row reduction breakdown")
        }
        work.set(pivot, value)
        return lastWork
    }

    fun transpose(work: BasisWorkspace): Long {
        lastWork = 0
        val value = work.values[pivot]
        if (value == 0.0) return 0
        work.scatter(-value, entries)
        lastWork = entries.count.toLong()
        return lastWork
    }
}
