package com.eignex.klause.simplex.basis

import com.eignex.klause.util.argsortBy
import com.eignex.koblas.sparse.SparseWorkspace
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
internal class ForrestTomlinFactors private constructor(
    factors: LuFactors,
    state: ForrestTomlinState?,
    takeOwnership: Boolean,
) {
    constructor(factors: LuFactors) : this(factors, null, false)

    private val sharedOrder = state?.order?.let { if (takeOwnership) it else it.copyOf() }
    val upper = if (state == null) {
        BasisTriangularMatrix(factors.upper)
    } else if (takeOwnership) {
        BasisTriangularMatrix(state.upper, checkNotNull(sharedOrder))
    } else {
        BasisTriangularMatrix(state.upper.map { it.copyOwned() }.toTypedArray(), checkNotNull(sharedOrder))
    }
    val transpose = if (state == null) {
        BasisTriangularMatrix(factors.upperTranspose)
    } else if (takeOwnership) {
        BasisTriangularMatrix(state.transpose, checkNotNull(sharedOrder))
    } else {
        BasisTriangularMatrix(state.transpose.map { it.copyOwned() }.toTypedArray(), checkNotNull(sharedOrder))
    }
    private val transforms = state?.transforms?.map {
        if (takeOwnership) it.restoreOwned() else it.restore()
    }?.toMutableList() ?: mutableListOf()
    private val n = upper.columns.size
    private val column = BasisWorkspace(n)
    private val row = BasisWorkspace(n)
    private val multipliers = BasisWorkspace(n)
    private val initialEntries = state?.initialEntries ?: factors.upper.nnz
    var upperEntries = state?.upperEntries ?: initialEntries
        private set
    var transformEntries = state?.transformEntries ?: 0
        private set
    val updateCount: Int get() = transforms.size
    var lastUpdateWork: ForrestTomlinWork? = state?.lastUpdateWork
        private set
    var lastSolveEntries = 0L
        private set

    fun snapshot(): ForrestTomlinState = ForrestTomlinState(
        upper.columns.map { it.copyOwned() }.toTypedArray(),
        transpose.columns.map { it.copyOwned() }.toTypedArray(),
        upper.order.copyOf(),
        transforms.map { it.snapshot() },
        initialEntries,
        upperEntries,
        transformEntries,
        lastUpdateWork,
    )

    fun extensionState(
        extendedUpper: Array<BasisSlice>,
        extendedTranspose: Array<BasisSlice>,
        extendedInitialEntries: Int,
        extendedUpperEntries: Int,
        offset: Int,
    ): ForrestTomlinState = ForrestTomlinState(
        extendedUpper,
        extendedTranspose,
        IntArray(offset) { it } + IntArray(upper.order.size) { upper.order[it] + offset },
        transforms.map { it.shiftedState(offset) },
        extendedInitialEntries,
        extendedUpperEntries,
        transformEntries,
        lastUpdateWork?.copy(
            upperEntries = extendedUpperEntries,
            transformEntries = transformEntries,
        ),
    )

    fun forward(work: BasisWorkspace): Long {
        lastSolveEntries = 0
        for (transform in transforms) {
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
        val gatheredIndices = IntArray(work.count)
        val gatheredValues = DoubleArray(work.count)
        val gathered = SparseWorkspace.gatherTouched(
            work.indices, 0, work.count, work.values,
            gatheredIndices, 0, gatheredValues, 0,
            compactExactZeros = true,
        )
        val order = argsortBy(gathered) { a, b -> gatheredIndices[a].compareTo(gatheredIndices[b]) }
        return BasisSlice(
            IntArray(gathered) { gatheredIndices[order[it]] },
            DoubleArray(gathered) { gatheredValues[order[it]] },
        )
    }

    companion object {
        fun restore(factors: LuFactors, state: ForrestTomlinState): ForrestTomlinFactors =
            ForrestTomlinFactors(factors, state, false)

        fun transfer(factors: LuFactors, state: ForrestTomlinState): ForrestTomlinFactors =
            ForrestTomlinFactors(factors, state, true)
    }
}

internal class ForrestTomlinRow(val pivot: Int, val entries: BasisSlice) {
    var lastWork = 0L
        private set

    fun snapshot() = ForrestTomlinRowState(pivot, entries.copyOwned())

    fun shiftedState(offset: Int) = ForrestTomlinRowState(
        pivot + offset,
        BasisSlice(
            IntArray(entries.count) { entries.indices[entries.offset + it] + offset },
            DoubleArray(entries.count) { entries.values[entries.offset + it] },
        ),
    )

    fun forward(work: BasisWorkspace): Long {
        lastWork = 0
        var value = work.values[pivot]
        for (k in 0 until entries.count) {
            lastWork = saturatedAdd(lastWork, 1)
            value = basisFinite(value - basisProduct(entries.values[k], work.values[entries.indices[k]]))
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

internal class ForrestTomlinState(
    val upper: Array<BasisSlice>,
    val transpose: Array<BasisSlice>,
    val order: IntArray,
    val transforms: List<ForrestTomlinRowState>,
    val initialEntries: Int,
    val upperEntries: Int,
    val transformEntries: Int,
    val lastUpdateWork: ForrestTomlinWork?,
)

internal class ForrestTomlinRowState(private val pivot: Int, private val entries: BasisSlice) {
    fun restore() = ForrestTomlinRow(pivot, entries.copyOwned())

    fun restoreOwned() = ForrestTomlinRow(pivot, entries)
}
