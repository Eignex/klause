package com.eignex.klause.simplex.basis

import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntDoubleMap
import com.eignex.koblas.SparseMatrix
import kotlin.math.abs
import kotlin.math.max

internal data class LuPivotPolicy(
    val absoluteTolerance: Double = 1e-10,
    val relativeThreshold: Double = 0.1,
    val searchLimit: Int = 8,
) {
    init {
        require(absoluteTolerance.isFinite() && absoluteTolerance >= 0.0)
        require(relativeThreshold.isFinite() && relativeThreshold > 0.0 && relativeThreshold <= 1.0)
        require(searchLimit > 0)
    }
}

internal data class LuBuildWork(
    val inputEntries: Int,
    val pivots: Int,
    val singletonPivots: Int,
    val kernelDimension: Int,
    val candidates: Long,
    val columnMaximumEntries: Long,
    val schurUpdates: Long,
    val fillCreated: Long,
    val factorEntries: Int,
)

internal data class LuBuildReport(
    val proposedOrder: Boolean,
    val reusedOrder: Boolean,
    val fallback: Boolean,
    val proposedRejection: LuBuildRejection?,
    val proposedWork: LuBuildWork?,
    val selectedWork: LuBuildWork,
) {
    val units: Long = saturatedAdd(proposedWork?.units ?: 0, selectedWork.units)
}

// These are floating-point declines, including exhausted numerical pivots, never exact rank claims.
internal enum class LuBuildRejection { NO_USABLE_PIVOT, NONFINITE_INPUT, ARITHMETIC_BREAKDOWN }

internal sealed interface LuBuildResult {
    val work: LuBuildWork
    val report: LuBuildReport

    class Built(val factors: LuFactors, override val work: LuBuildWork, override val report: LuBuildReport) :
        LuBuildResult

    class Rejected(val reason: LuBuildRejection, override val work: LuBuildWork, override val report: LuBuildReport) :
        LuBuildResult
}

// Buffers belong exclusively to this cache; updates must not alias another build.
// L omits its unit diagonal; U includes its diagonal. Transposes provide row adjacency for reach.
internal class LuFactors(
    val basisColumns: IntArray,
    val basisUnitRows: IntArray,
    val symbolic: SymbolicLu,
    val lower: SparseMatrix,
    val upper: SparseMatrix,
    val lowerTranspose: SparseMatrix,
    val upperTranspose: SparseMatrix,
)

internal class F64BasisFactors(matrix: SparseMatrix) {
    private val source = SparseMatrix.wrap(
        matrix.rows,
        matrix.cols,
        matrix.copyColumnPointers(),
        matrix.copyRowIndices(),
        matrix.values.copyOf(),
    )
    val dimension: Int = source.rows

    fun build(basisColumns: IntArray, policy: LuPivotPolicy = LuPivotPolicy()): LuBuildResult =
        build(basisColumns, IntArray(dimension) { -1 }, policy)

    fun build(
        basisColumns: IntArray,
        unitRows: IntArray,
        policy: LuPivotPolicy = LuPivotPolicy(),
        proposedOrder: SymbolicLu? = null,
    ): LuBuildResult {
        require(basisColumns.size == dimension)
        require(unitRows.size == dimension)
        for (slot in basisColumns.indices) {
            require(basisColumns[slot] == -1 || basisColumns[slot] in 0 until source.cols)
            require(unitRows[slot] == -1 || unitRows[slot] in 0 until dimension)
            require((basisColumns[slot] in 0 until source.cols) != (unitRows[slot] in 0 until dimension))
        }
        val columns = basisColumns.copyOf()
        val units = unitRows.copyOf()
        val proposal = proposedOrder?.let { copyProposal(it, dimension) }
        if (proposal != null) {
            val proposed = LuConstruction(source, columns, units, policy, proposal).build()
            if (proposed is LuAttemptResult.Built) {
                return proposed.result(
                    LuBuildReport(true, true, false, null, null, proposed.work),
                )
            }
            val rejected = proposed as LuAttemptResult.Rejected
            val fallback = LuConstruction(source, columns, units, policy, null).build()
            return fallback.result(
                LuBuildReport(
                    true,
                    false,
                    true,
                    rejected.reason,
                    rejected.work,
                    fallback.work,
                ),
            )
        }
        if (proposedOrder != null) {
            val fallback = LuConstruction(source, columns, units, policy, null).build()
            return fallback.result(
                LuBuildReport(
                    true,
                    false,
                    true,
                    LuBuildRejection.NO_USABLE_PIVOT,
                    EMPTY_LU_BUILD_WORK,
                    fallback.work,
                ),
            )
        }
        val fresh = LuConstruction(source, columns, units, policy, null).build()
        return fresh.result(LuBuildReport(false, false, false, null, null, fresh.work))
    }
}

private val EMPTY_LU_BUILD_WORK = LuBuildWork(0, 0, 0, 0, 0, 0, 0, 0, 0)

private fun copyProposal(proposal: SymbolicLu, dimension: Int): SymbolicLu? {
    val rows = proposal.rowOrder.copyOf()
    val columns = proposal.columnOrder.copyOf()
    if (!rows.isPermutation(dimension) || !columns.isPermutation(dimension)) return null
    return SymbolicLu(rows, columns)
}

private fun IntArray.isPermutation(dimension: Int): Boolean {
    if (size != dimension) return false
    val seen = BooleanArray(dimension)
    for (value in this) {
        if (value !in 0 until dimension || seen[value]) return false
        seen[value] = true
    }
    return true
}

private sealed interface LuAttemptResult {
    val work: LuBuildWork

    class Built(val factors: LuFactors, override val work: LuBuildWork) : LuAttemptResult
    class Rejected(val reason: LuBuildRejection, override val work: LuBuildWork) : LuAttemptResult
}

private fun LuAttemptResult.result(report: LuBuildReport): LuBuildResult = when (this) {
    is LuAttemptResult.Built -> LuBuildResult.Built(factors, work, report)
    is LuAttemptResult.Rejected -> LuBuildResult.Rejected(reason, work, report)
}

private class LuConstruction(
    private val source: SparseMatrix,
    private val basisColumns: IntArray,
    private val unitRows: IntArray,
    private val policy: LuPivotPolicy,
    private val proposedOrder: SymbolicLu?,
) {
    private val n = source.rows
    private val columns = Array(n) { MutableIntDoubleMap() }
    private val rows = Array(n) { IntHashSet() }
    private val rowBuckets = LuCountBuckets(n)
    private val columnBuckets = LuCountBuckets(n)
    private val columnMax = DoubleArray(n)
    private val rowOrder = IntArray(n)
    private val columnOrder = IntArray(n)
    private val lower = LuEntries()
    private val upper = LuEntries()
    private var inputEntries = 0
    private var pivots = 0
    private var singletonPivots = 0
    private var kernelDimension = 0
    private var candidates = 0L
    private var maximumEntries = 0L
    private var schurUpdates = 0L
    private var fillCreated = 0L

    fun build(): LuAttemptResult {
        if (!load()) return rejected(LuBuildRejection.NONFINITE_INPUT)
        var inKernel = false
        while (pivots < n) {
            val proposed = proposedOrder?.let {
                LuPivot(it.rowOrder[pivots], it.columnOrder[pivots]).takeIf { pivot ->
                    acceptable(pivot.row, pivot.column)
                } ?: return rejected(LuBuildRejection.NO_USABLE_PIVOT)
            }
            val singleton = if (proposed == null) singleton() else null
            if (proposed == null && singleton == null && !inKernel) {
                inKernel = true
                kernelDimension = n - pivots
            }
            val pivot = proposed ?: singleton ?: markowitz() ?: return rejected(LuBuildRejection.NO_USABLE_PIVOT)
            if (!eliminate(pivot)) return rejected(LuBuildRejection.ARITHMETIC_BREAKDOWN)
            if (singleton != null) singletonPivots++
            rowOrder[pivots] = pivot.row
            columnOrder[pivots] = pivot.column
            pivots++
        }
        val symbolic = SymbolicLu(rowOrder, columnOrder)
        val l = lower.matrix(n, symbolic.rowPosition, null)
        val u = upper.matrix(n, null, symbolic.columnPosition)
        val lt = lower.matrix(n, symbolic.rowPosition, null, transpose = true)
        val ut = upper.matrix(n, null, symbolic.columnPosition, transpose = true)
        return LuAttemptResult.Built(LuFactors(basisColumns, unitRows, symbolic, l, u, lt, ut), work())
    }

    private fun load(): Boolean {
        var finite = true
        for (j in 0 until n) {
            val unitRow = unitRows[j]
            if (unitRow >= 0) {
                inputEntries++
                columns[j].put(unitRow, 1.0)
                rows[unitRow].add(j)
            } else {
                source.forEachInColumn(basisColumns[j]) { i, value ->
                    inputEntries++
                    if (!value.isFinite()) finite = false
                    if (value != 0.0) {
                        columns[j].put(i, value)
                        rows[i].add(j)
                    }
                }
            }
            refreshMaximum(j)
        }
        for (i in n - 1 downTo 0) {
            rowBuckets.move(i, rows[i].size)
            columnBuckets.move(i, columns[i].size)
        }
        return finite
    }

    private fun singleton(): LuPivot? {
        var j = columnBuckets.first(1)
        while (j >= 0) {
            var row = -1
            columns[j].forEach { i, _ -> row = i }
            if (acceptable(row, j)) return LuPivot(row, j)
            j = columnBuckets.next(j)
        }
        var i = rowBuckets.first(1)
        while (i >= 0) {
            var column = -1
            rows[i].forEach { column = it }
            if (acceptable(i, column)) return LuPivot(i, column)
            i = rowBuckets.next(i)
        }
        return null
    }

    private fun acceptable(row: Int, column: Int): Boolean {
        candidates++
        val magnitude = abs(columns[column].getOrDefault(row, 0.0))
        return magnitude > 0.0 && magnitude >= policy.absoluteTolerance &&
            magnitude >= policy.relativeThreshold * columnMax[column]
    }

    private fun markowitz(): LuPivot? {
        var best: LuPivot? = null
        var bestMerit = Long.MAX_VALUE
        var bestMagnitude = 0.0
        var searched = 0
        fun consider(i: Int, j: Int) {
            if (!acceptable(i, j)) return
            val merit = (rows[i].size - 1).toLong() * (columns[j].size - 1)
            val magnitude = abs(columns[j].getOrDefault(i, 0.0))
            val previous = best
            val better = when {
                merit != bestMerit -> merit < bestMerit
                magnitude != bestMagnitude -> magnitude > bestMagnitude
                previous == null -> true
                j != previous.column -> j < previous.column
                else -> i < previous.row
            }
            if (better) {
                best = LuPivot(i, j)
                bestMerit = merit
                bestMagnitude = magnitude
            }
        }
        for (count in 2..n) {
            var j = columnBuckets.first(count)
            while (j >= 0) {
                columns[j].forEach { i, _ -> consider(i, j) }
                searched++
                if (best != null && searched >= policy.searchLimit) return best
                j = columnBuckets.next(j)
            }
            var i = rowBuckets.first(count)
            while (i >= 0) {
                rows[i].forEach { column -> consider(i, column) }
                searched++
                if (best != null && searched >= policy.searchLimit) return best
                i = rowBuckets.next(i)
            }
            // All smaller counts were inspected. Unvisited entries have merit at least count squared.
            if (best != null && bestMerit <= count.toLong() * count) return best
        }
        return best
    }

    private fun eliminate(pivot: LuPivot): Boolean {
        val i = pivot.row
        val j = pivot.column
        val diagonal = columns[j].getOrDefault(i, 0.0)
        val pivotRows = IntArrayList(columns[j].size)
        columns[j].forEach { row, _ -> if (row != i) pivotRows.add(row) }
        val affectedRows = pivotRows.toIntArray().also { it.sort() }
        val affectedColumns = rows[i].toIntArray().also { it.sort() }
        val multipliers = DoubleArray(affectedRows.size)
        for (k in affectedRows.indices) {
            val multiplier = columns[j].getOrDefault(affectedRows[k], 0.0) / diagonal
            if (!multiplier.isFinite() || multiplier == 0.0) return false
            multipliers[k] = multiplier
            lower.add(affectedRows[k], pivots, multiplier)
        }
        rowBuckets.remove(i)
        columnBuckets.remove(j)
        for (column in affectedColumns) {
            val top = columns[column].getOrDefault(i, 0.0)
            upper.add(pivots, column, top)
            columns[column].remove(i)
            if (column == j) continue
            for (k in affectedRows.indices) {
                val row = affectedRows[k]
                val product = multipliers[k] * top
                val before = columns[column].getOrDefault(row, 0.0)
                val after = before - product
                schurUpdates++
                if (!product.isFinite() || product == 0.0 || !after.isFinite()) return false
                when {
                    after == 0.0 -> {
                        columns[column].remove(row)
                        rows[row].remove(column)
                    }

                    else -> {
                        columns[column].put(row, after)
                        if (before == 0.0) {
                            rows[row].add(column)
                            fillCreated++
                        }
                    }
                }
            }
            columnBuckets.move(column, columns[column].size)
            refreshMaximum(column)
        }
        for (row in affectedRows) {
            rows[row].remove(j)
            rowBuckets.move(row, rows[row].size)
        }
        columns[j].clear()
        rows[i].clear()
        return true
    }

    private fun refreshMaximum(column: Int) {
        var maximum = 0.0
        columns[column].forEach { _, value ->
            maximumEntries++
            maximum = max(maximum, abs(value))
        }
        columnMax[column] = maximum
    }

    private fun work() = LuBuildWork(
        inputEntries, pivots, singletonPivots, kernelDimension, candidates, maximumEntries,
        schurUpdates, fillCreated, lower.size + upper.size,
    )

    private fun rejected(reason: LuBuildRejection) = LuAttemptResult.Rejected(reason, work())
}

internal fun LuFactors.copyOwned(): LuFactors = LuFactors(
    basisColumns.copyOf(),
    basisUnitRows.copyOf(),
    SymbolicLu(symbolic.rowOrder.copyOf(), symbolic.columnOrder.copyOf()),
    lower.copyOwned(),
    upper.copyOwned(),
    lowerTranspose.copyOwned(),
    upperTranspose.copyOwned(),
)

private fun SparseMatrix.copyOwned(): SparseMatrix = SparseMatrix.wrap(
    rows,
    cols,
    copyColumnPointers(),
    copyRowIndices(),
    values.copyOf(),
)

private class LuPivot(val row: Int, val column: Int)

private class LuEntries {
    private val rows = IntArrayList()
    private val columns = IntArrayList()
    private var values = DoubleArray(8)
    val size: Int get() = rows.size

    fun add(row: Int, column: Int, value: Double) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size] = value
        rows.add(row)
        columns.add(column)
    }

    fun matrix(n: Int, rowMap: IntArray?, columnMap: IntArray?, transpose: Boolean = false): SparseMatrix {
        val row = IntArray(size) { rowMap?.get(rows[it]) ?: rows[it] }
        val column = IntArray(size) { columnMap?.get(columns[it]) ?: columns[it] }
        return SparseMatrix.ofTriplets(
            n,
            n,
            if (transpose) column else row,
            if (transpose) row else column,
            values.copyOf(size),
        )
    }
}
