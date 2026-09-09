package com.eignex.klause.simplex.basis

import com.eignex.klause.util.binarySearchInt
import com.eignex.koblas.ExperimentalKoblasApi
import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.sparse.SparseWorkspace

internal data class TriangularSolveWork(
    val sparse: Boolean,
    val reached: Int,
    val reachEntries: Long,
    val pivotVisits: Int,
    val arithmeticEntries: Long,
)

// Exclusive scratch storage. Membership survives exact cancellation until clear; no tolerance dropping.
@OptIn(ExperimentalKoblasApi::class)
internal class BasisWorkspace(val size: Int) {
    val values = DoubleArray(size)
    val indices = IntArray(size)
    private val marks = IntArray(size)
    private val arithmeticStatus = IntArray(1)
    var count = 0
        private set

    fun set(i: Int, value: Double) {
        if (marks[i] == 0) {
            marks[i] = 1
            indices[count++] = i
        }
        values[i] = value
    }

    fun clear() {
        for (k in 0 until count) {
            val i = indices[k]
            values[i] = 0.0
            marks[i] = 0
        }
        count = 0
    }

    fun scatter(alpha: Double, slice: BasisSlice, start: Int = 0, length: Int = slice.count) {
        arithmeticStatus[0] = 0
        count = SparseWorkspace.scatterAxpyChecked(
            alpha, slice.indices, slice.offset + start, slice.values, slice.offset + start, length,
            values, marks, 1, indices, 0, count, arithmeticStatus, 0,
        )
        if (arithmeticStatus[0] != 0) throw ArithmeticException("checked basis scatter breakdown")
    }

    fun load(vector: IndexedVector, position: IntArray? = null) {
        clear()
        vector.forEachStored { i, value ->
            require(value.isFinite()) { "nonfinite right-hand side" }
            if (value != 0.0) set(position?.get(i) ?: i, value)
        }
    }

    fun write(vector: IndexedVector, order: IntArray? = null) {
        vector.clear()
        for (k in 0 until count) {
            val i = indices[k]
            if (values[i] != 0.0) vector.store(order?.get(i) ?: i, values[i])
        }
    }
}

// Owns reach scratch and structural copies; numerical values belong to the enclosing factor cache.
// CSC edges run from a solved column to its dependent rows. Reverse DFS postorder is a solve order.
internal class HyperSparseSolve(
    private val matrix: BasisTriangularMatrix,
    private val lower: Boolean,
    private val unitDiagonal: Boolean,
    private val densityThreshold: Double = 0.2,
) {
    constructor(
        matrix: SparseMatrix,
        lower: Boolean,
        unitDiagonal: Boolean,
        densityThreshold: Double = 0.2,
    ) : this(BasisTriangularMatrix(matrix), lower, unitDiagonal, densityThreshold)

    private val n = matrix.columns.size
    private val seen = BooleanArray(n)
    private val reached = IntArray(n)
    private val stack = IntArray(n)
    private val cursor = IntArray(n)
    private val postorder = IntArray(n)
    private var reachCount = 0
    private var postCount = 0
    private var reachEntries = 0L

    init {
        require(densityThreshold.isFinite() && densityThreshold in 0.0..1.0)
    }

    fun solve(work: BasisWorkspace, expectedDensity: Double): TriangularSolveWork {
        require(work.size == n)
        require(expectedDensity.isFinite() && expectedDensity in 0.0..1.0)
        reachCount = 0
        postCount = 0
        reachEntries = 0
        val sparse = expectedDensity <= densityThreshold && reach(work)
        var visits = 0
        var arithmetic = 0L
        if (sparse) {
            for (k in postCount - 1 downTo 0) {
                arithmetic += eliminate(postorder[k], work)
                visits++
            }
        } else {
            for (k in 0 until n) {
                arithmetic += eliminate(matrix.order[if (lower) k else n - 1 - k], work)
                visits++
            }
        }
        return TriangularSolveWork(sparse, reachCount, reachEntries, visits, arithmetic)
    }

    private fun reach(work: BasisWorkspace): Boolean {
        try {
            for (k in 0 until work.count) {
                val root = work.indices[k]
                if (work.values[root] == 0.0 || seen[root]) continue
                var depth = 0
                stack[0] = root
                cursor[0] = 0
                seen[root] = true
                reached[reachCount++] = root
                while (depth >= 0) {
                    if (reachCount > densityThreshold * n) return false
                    val j = stack[depth]
                    if (cursor[depth] == matrix.columns[j].count) {
                        postorder[postCount++] = j
                        depth--
                        continue
                    }
                    val column = matrix.columns[j]
                    val entry = column.offset + cursor[depth]++
                    reachEntries++
                    val i = column.indices[entry]
                    if (i == j || column.values[entry] == 0.0 || seen[i]) continue
                    seen[i] = true
                    reached[reachCount++] = i
                    depth++
                    stack[depth] = i
                    cursor[depth] = 0
                }
            }
            return true
        } finally {
            for (k in 0 until reachCount) seen[reached[k]] = false
        }
    }

    private fun eliminate(j: Int, work: BasisWorkspace): Long {
        val rhs = work.values[j]
        if (rhs == 0.0) return 0
        val column = matrix.columns[j]
        val pivot = if (unitDiagonal) 1.0 else column[j]
        val value = basisQuotient(rhs, pivot)
        // The diagonal is a solve, not a scatter contribution. CSC support is sorted.
        var diagonal = 0
        while (diagonal < column.count && column.indices[column.offset + diagonal] < j) diagonal++
        work.scatter(-value, column, length = diagonal)
        val after = diagonal + if (diagonal < column.count && column.indices[column.offset + diagonal] == j) 1 else 0
        work.scatter(-value, column, start = after, length = column.count - after)
        work.set(j, value)
        val entries = (diagonal + column.count - after).toLong()
        return entries
    }
}

internal fun basisFinite(value: Double): Double {
    if (!value.isFinite()) throw ArithmeticException("nonfinite basis arithmetic")
    return value
}

internal fun basisProduct(a: Double, b: Double): Double {
    val result = basisFinite(a * b)
    if (result == 0.0 && a != 0.0 && b != 0.0) throw ArithmeticException("basis product underflow")
    return result
}

internal fun basisQuotient(a: Double, b: Double): Double {
    val result = basisFinite(a / b)
    if (result == 0.0 && a != 0.0) throw ArithmeticException("basis quotient underflow")
    return result
}

internal class BasisSlice(
    val indices: IntArray,
    val values: DoubleArray,
    val offset: Int = 0,
    val count: Int = indices.size,
) {
    operator fun get(index: Int): Double {
        val found = indices.binarySearchInt(index, offset, offset + count)
        return if (found < 0) 0.0 else values[found]
    }
}

internal class BasisTriangularMatrix(var columns: Array<BasisSlice>, var order: IntArray) {
    constructor(matrix: SparseMatrix) : this(slices(matrix), IntArray(matrix.rows) { it }) {
        require(matrix.rows == matrix.cols)
    }

    companion object {
        private fun slices(matrix: SparseMatrix): Array<BasisSlice> {
            val pointers = matrix.copyColumnPointers()
            val indices = matrix.copyRowIndices()
            return Array(matrix.cols) { j ->
                BasisSlice(indices, matrix.values, pointers[j], pointers[j + 1] - pointers[j])
            }
        }
    }
}
