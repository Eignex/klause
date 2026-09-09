package com.eignex.klause.simplex.basis

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

    fun forward(work: BasisWorkspace): Long {
        var entries = 0L
        for (transform in transforms) entries += transform.forward(work)
        return entries
    }

    fun backward(work: BasisWorkspace): Long {
        var entries = 0L
        for (i in transforms.size - 1 downTo 0) entries += transforms[i].transpose(work)
        return entries
    }

    fun fillAdvice(factor: Double): Boolean = upperEntries.toDouble() + transformEntries > factor * initialEntries

    fun update(pivot: Int, spike: BasisWorkspace, tolerance: Double): Boolean {
        column.clear()
        row.clear()
        multipliers.clear()
        var columnProducts = 0L
        var rowProducts = 0L
        var copiedEntries = 0L
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
            row.scatter(1.0, rows[pivot])
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
            return true
        } catch (_: ArithmeticException) {
            return false
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
        val indices = work.indices.copyOf(work.count).filter { work.values[it] != 0.0 }.toIntArray()
        indices.sort()
        return BasisSlice(indices, DoubleArray(indices.size) { work.values[indices[it]] })
    }
}

private class ForrestTomlinRow(val pivot: Int, val entries: BasisSlice) {
    fun forward(work: BasisWorkspace): Long {
        var value = work.values[pivot]
        for (k in 0 until entries.count) {
            value = basisFinite(value - basisProduct(entries.values[k], work.values[entries.indices[k]]))
        }
        work.set(pivot, value)
        return entries.count.toLong()
    }

    fun transpose(work: BasisWorkspace): Long {
        val value = work.values[pivot]
        if (value == 0.0) return 0
        work.scatter(-value, entries)
        return entries.count.toLong()
    }
}
