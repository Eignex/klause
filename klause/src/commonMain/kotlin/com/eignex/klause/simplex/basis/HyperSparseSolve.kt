package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.sparse.basis.IndexedVector

internal data class TriangularSolveWork(
    val sparse: Boolean,
    val reached: Int,
    val reachEntries: Long,
    val pivotVisits: Int,
    val arithmeticEntries: Long,
)

// Exclusive scratch storage. Membership survives exact cancellation until clear; no tolerance dropping.
internal class BasisWorkspace(val size: Int) {
    val values = DoubleArray(size)
    val indices = IntArray(size)
    private val present = BooleanArray(size)
    var count = 0
        private set

    fun set(i: Int, value: Double) {
        if (!present[i]) {
            present[i] = true
            indices[count++] = i
        }
        values[i] = value
    }

    fun clear() {
        for (k in 0 until count) {
            val i = indices[k]
            values[i] = 0.0
            present[i] = false
        }
        count = 0
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
    private val matrix: SparseMatrix,
    private val lower: Boolean,
    private val unitDiagonal: Boolean,
    private val densityThreshold: Double = 0.2,
) {
    private val n = matrix.rows
    private val pointers = matrix.copyColumnPointers()
    private val rows = matrix.copyRowIndices()
    private val seen = BooleanArray(n)
    private val reached = IntArray(n)
    private val stack = IntArray(n)
    private val cursor = IntArray(n)
    private val postorder = IntArray(n)
    private var reachCount = 0
    private var postCount = 0
    private var reachEntries = 0L

    init {
        require(matrix.cols == n)
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
                arithmetic += eliminate(if (lower) k else n - 1 - k, work)
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
                cursor[0] = pointers[root]
                seen[root] = true
                reached[reachCount++] = root
                while (depth >= 0) {
                    if (reachCount > densityThreshold * n) return false
                    val j = stack[depth]
                    if (cursor[depth] == pointers[j + 1]) {
                        postorder[postCount++] = j
                        depth--
                        continue
                    }
                    val entry = cursor[depth]++
                    reachEntries++
                    val i = rows[entry]
                    if (i == j || matrix.values[entry] == 0.0 || seen[i]) continue
                    seen[i] = true
                    reached[reachCount++] = i
                    depth++
                    stack[depth] = i
                    cursor[depth] = pointers[i]
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
        val pivot = if (unitDiagonal) 1.0 else matrix[j, j]
        val value = basisQuotient(rhs, pivot)
        work.set(j, value)
        var entries = 0L
        for (entry in pointers[j] until pointers[j + 1]) {
            val i = rows[entry]
            if (i == j) continue
            val product = basisProduct(matrix.values[entry], value)
            work.set(i, basisFinite(work.values[i] - product))
            entries++
        }
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
