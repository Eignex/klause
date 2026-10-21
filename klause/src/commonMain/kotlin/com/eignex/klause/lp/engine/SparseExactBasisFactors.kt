package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisBuild
import com.eignex.klause.simplex.basis.RationalBasisFactors
import com.eignex.klause.simplex.basis.RationalBasisSolve
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.IntArrayList

internal interface ExactBasisFactorization {
    fun solve(rhs: List<BigFraction>, transpose: Boolean, meter: ExactBasisMeter): List<BigFraction>
}

internal class DenseExactBasisFactorization(private val factors: RationalBasisFactors) : ExactBasisFactorization {
    override fun solve(rhs: List<BigFraction>, transpose: Boolean, meter: ExactBasisMeter): List<BigFraction> =
        solveDenseExactBasis(factors, rhs, transpose, meter)
}

private fun solveDenseExactBasis(
    factors: RationalBasisFactors,
    rhs: List<BigFraction>,
    transpose: Boolean,
    meter: ExactBasisMeter,
): List<BigFraction> {
    val result = factors.solve(rhs, transpose, meter.factorLimits(), meter.token)
    meter.record(result.stats)
    return when (result) {
        is RationalBasisSolve.Solved -> result.values
        is RationalBasisSolve.Declined -> {
            if (result.reason.name == "CANCELLED") meter.poll()
            meter.stop(ExactBasisDecline.valueOf(result.reason.name))
        }
    }
}

private class SparseBasisPivot(val row: Int, val column: Int, val value: BigFraction, val rowSingleton: Boolean)
private class SparseBasisBlock(val rows: IntArray, val columns: IntArray, val factors: RationalBasisFactors)

private class SparseExactBasisFactorization(
    private val rows: Array<out List<Pair<Int, BigFraction>>>,
    private val columns: Array<out List<Pair<Int, BigFraction>>>,
    private val pivots: List<SparseBasisPivot>,
    private val rowOrder: IntArray,
    private val columnOrder: IntArray,
    private val blocks: List<SparseBasisBlock>,
    private val fill: Int,
) : ExactBasisFactorization {
    override fun solve(rhs: List<BigFraction>, transpose: Boolean, meter: ExactBasisMeter): List<BigFraction> {
        meter.poll()
        require(rhs.size == rows.size) { "RHS dimension mismatch" }
        if (fill > meter.limits.factor.fill) meter.stop(ExactBasisDecline.FILL)
        meter.peakFill = maxOf(meter.peakFill, fill)
        meter.charge(rows.size * 4L, rows.size * 32L)
        val remaining = rhs.map(meter::fraction).toMutableList()
        val result = MutableList(rows.size) { BigFraction.ZERO }
        // Transposition swaps forward row singletons with deferred column singletons.
        for ((order, pivot) in pivots.withIndex()) {
            meter.charge()
            if (pivot.rowSingleton == transpose) continue
            val input = if (transpose) pivot.column else pivot.row
            val output = if (transpose) pivot.row else pivot.column
            val value = meter.divide(remaining[input], pivot.value)
            result[output] = value
            if (value.isZero) continue
            val neighbors = if (transpose) rows[pivot.row] else columns[pivot.column]
            val positions = if (transpose) columnOrder else rowOrder
            for ((index, coefficient) in neighbors) {
                meter.charge()
                if (positions[index] > order) {
                    remaining[index] = meter.subtractProduct(remaining[index], coefficient, value)
                }
            }
        }
        for (block in blocks) {
            val inputs = if (transpose) block.columns else block.rows
            val outputs = if (transpose) block.rows else block.columns
            meter.charge(inputs.size.toLong(), inputs.size * 16L)
            val solved = solveDenseExactBasis(block.factors, inputs.map { remaining[it] }, transpose, meter)
            for (index in outputs.indices) result[outputs[index]] = solved[index]
        }
        for (order in pivots.indices.reversed()) {
            meter.charge()
            val pivot = pivots[order]
            if (pivot.rowSingleton != transpose) continue
            val input = if (transpose) pivot.column else pivot.row
            val output = if (transpose) pivot.row else pivot.column
            val neighbors = if (transpose) columns[pivot.column] else rows[pivot.row]
            val positions = if (transpose) rowOrder else columnOrder
            var value = remaining[input]
            for ((index, coefficient) in neighbors) {
                meter.charge()
                if (positions[index] > order && !result[index].isZero) {
                    value = meter.subtractProduct(value, coefficient, result[index])
                }
            }
            result[output] = meter.divide(value, pivot.value)
        }
        meter.poll()
        return result
    }
}

internal fun buildSparseExactBasis(authority: ExactBasisAuthority): ExactBasisFactorization =
    SparseExactBasisBuilder(authority).build()

private class SparseExactBasisBuilder(private val authority: ExactBasisAuthority) {
    private val meter = authority.meter
    private val size = authority.model.m
    private val rows: Array<ArrayList<Pair<Int, BigFraction>>>
    private val columns: Array<ArrayList<Pair<Int, BigFraction>>>
    private val rowDegree: IntArray
    private val columnDegree: IntArray
    private val rowOrder: IntArray
    private val columnOrder: IntArray
    private val rowQueue: IntArray
    private val columnQueue: IntArray
    private val pivots: ArrayList<SparseBasisPivot>
    private var rowHead = 0
    private var rowTail = 0
    private var columnHead = 0
    private var columnTail = 0
    private var fill = 0

    init {
        meter.charge(size * 8L, size * 256L)
        rows = Array(size) { ArrayList() }
        columns = Array(size) { ArrayList() }
        rowDegree = IntArray(size)
        columnDegree = IntArray(size)
        rowOrder = IntArray(size) { Int.MAX_VALUE }
        columnOrder = IntArray(size) { Int.MAX_VALUE }
        rowQueue = IntArray(size)
        columnQueue = IntArray(size)
        pivots = ArrayList(size)
        for (column in authority.headings.indices) {
            for ((row, value) in authority.matrix[authority.headings[column]]) {
                meter.charge()
                if (value.isZero) continue
                if (++fill > meter.limits.factor.fill) meter.stop(ExactBasisDecline.FILL)
                meter.charge(bytes = 64L)
                rows[row].add(column to value)
                columns[column].add(row to value)
                rowDegree[row]++
                columnDegree[column]++
            }
        }
        meter.peakFill = maxOf(meter.peakFill, fill)
        for (index in 0 until size) {
            meter.charge()
            if (rowDegree[index] == 0 || columnDegree[index] == 0) meter.stop(ExactBasisDecline.SINGULAR)
            if (rowDegree[index] == 1) rowQueue[rowTail++] = index
            if (columnDegree[index] == 1) columnQueue[columnTail++] = index
        }
    }

    fun build(): ExactBasisFactorization {
        while (rowHead < rowTail || columnHead < columnTail) {
            meter.charge()
            if (rowHead < rowTail) {
                val row = rowQueue[rowHead++]
                if (rowOrder[row] != Int.MAX_VALUE || rowDegree[row] != 1) continue
                val (column, value) = rows[row].first {
                    meter.charge()
                    columnOrder[it.first] == Int.MAX_VALUE
                }
                remove(row, column, value, rowSingleton = true)
            } else {
                val column = columnQueue[columnHead++]
                if (columnOrder[column] != Int.MAX_VALUE || columnDegree[column] != 1) continue
                val (row, value) = columns[column].first {
                    meter.charge()
                    rowOrder[it.first] == Int.MAX_VALUE
                }
                remove(row, column, value, rowSingleton = false)
            }
        }
        val blocks = residualBlocks()
        meter.poll()
        return SparseExactBasisFactorization(rows, columns, pivots, rowOrder, columnOrder, blocks, fill)
    }

    private fun remove(row: Int, column: Int, value: BigFraction, rowSingleton: Boolean) {
        // Singleton elimination creates no fill, so the coefficient adjacency stays immutable.
        val order = pivots.size
        rowOrder[row] = order
        columnOrder[column] = order
        pivots.add(SparseBasisPivot(row, column, value, rowSingleton))
        for ((other, _) in columns[column]) {
            meter.charge()
            if (rowOrder[other] != Int.MAX_VALUE) continue
            if (--rowDegree[other] == 0) meter.stop(ExactBasisDecline.SINGULAR)
            if (rowDegree[other] == 1) rowQueue[rowTail++] = other
        }
        for ((other, _) in rows[row]) {
            meter.charge()
            if (columnOrder[other] != Int.MAX_VALUE) continue
            if (--columnDegree[other] == 0) meter.stop(ExactBasisDecline.SINGULAR)
            if (columnDegree[other] == 1) columnQueue[columnTail++] = other
        }
    }

    private fun residualBlocks(): List<SparseBasisBlock> {
        meter.charge(size * 6L, size * 64L)
        val parent = IntArray(size) { it }
        fun find(column: Int): Int {
            var root = column
            while (parent[root] != root) {
                meter.charge()
                parent[root] = parent[parent[root]]
                root = parent[root]
            }
            return root
        }
        for (row in rows.indices) {
            if (rowOrder[row] != Int.MAX_VALUE) continue
            var first = -1
            for ((column, _) in rows[row]) {
                meter.charge()
                if (columnOrder[column] != Int.MAX_VALUE) continue
                val root = find(column)
                if (first < 0) first = root else parent[root] = find(first)
            }
        }
        val label = IntArray(size) { -1 }
        val blockColumns = ArrayList<IntArrayList>()
        val blockRows = ArrayList<IntArrayList>()
        for (column in columns.indices) {
            meter.charge()
            if (columnOrder[column] != Int.MAX_VALUE) continue
            val root = find(column)
            if (label[root] < 0) {
                label[root] = blockColumns.size
                blockColumns.add(IntArrayList())
                blockRows.add(IntArrayList())
            }
            blockColumns[label[root]].add(column)
        }
        for (row in rows.indices) {
            meter.charge()
            if (rowOrder[row] != Int.MAX_VALUE) continue
            val column = rows[row].first {
                meter.charge()
                columnOrder[it.first] == Int.MAX_VALUE
            }.first
            blockRows[label[find(column)]].add(row)
        }
        val local = IntArray(size) { -1 }
        return blockColumns.indices.map { block ->
            val cs = blockColumns[block].toIntArray()
            val rs = blockRows[block].toIntArray()
            if (cs.size != rs.size) meter.stop(ExactBasisDecline.SINGULAR)
            if (cs.size > meter.limits.factor.dimension) meter.stop(ExactBasisDecline.DIMENSION)
            meter.charge(cs.size.toLong() * cs.size, cs.size.toLong() * cs.size * 8L + cs.size * 96L)
            for (index in cs.indices) local[cs[index]] = index
            var entries = 0
            val matrix = rs.map { row ->
                val values = MutableList(cs.size) { BigFraction.ZERO }
                for ((column, value) in rows[row]) {
                    meter.charge()
                    if (columnOrder[column] == Int.MAX_VALUE) {
                        values[local[column]] = value
                        entries++
                    }
                }
                values
            }
            val outside = fill - entries
            val allowance = meter.factorLimits().copy(fill = (meter.limits.factor.fill - outside).coerceAtLeast(0))
            meter.factoryCalls++
            val built = RationalBasisFactors.factor(matrix, limits = allowance, cancellation = meter.token)
            meter.record(built.stats)
            meter.peakFill = maxOf(meter.peakFill, outside + built.stats.peakFill)
            val factors = when (built) {
                is RationalBasisBuild.Ready -> built.factors
                is RationalBasisBuild.Singular -> meter.stop(ExactBasisDecline.SINGULAR)
                is RationalBasisBuild.Declined -> {
                    if (built.reason.name == "CANCELLED") meter.poll()
                    meter.stop(ExactBasisDecline.valueOf(built.reason.name))
                }
            }
            fill = outside + built.stats.peakFill
            meter.peakFill = maxOf(meter.peakFill, fill)
            SparseBasisBlock(rs, cs, factors)
        }
    }
}
