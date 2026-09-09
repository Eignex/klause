package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class BasisSolveWork(
    val first: TriangularSolveWork,
    val second: TriangularSolveWork,
    val transformEntries: Long,
    val outputSupport: Int,
)

// Single-threaded owner of source, factors, row transforms and scratch. Caller vectors never become retained buffers.
// Mandatory operations/reports reject close; n and the last refactorization's singular flag remain readable.
// Optional repair/snapshot behavior is exactly the seam default. No cancellation or resource API is exposed.
internal class KotlinBasisSolver(
    matrix: SparseMatrix,
    private val policy: LuPivotPolicy = LuPivotPolicy(),
    private val updateLimit: Int = 50,
    private val fillFactor: Double = 5.0,
    private val densityThreshold: Double = 0.2,
) : BasisSolver {
    private val source = SparseMatrix.wrap(
        matrix.rows,
        matrix.cols,
        matrix.copyColumnPointers(),
        matrix.copyRowIndices(),
        matrix.values.copyOf(),
    )
    private val builder = F64BasisFactors(source)
    override val n = source.rows
    private val work = BasisWorkspace(n)
    private val mapped = BasisWorkspace(n)
    private var cache: BasisSolveCache? = null
    private var columns = IntArray(0)
    private var closed = false
    override var singular = true
        private set
    var lastSolveWork: BasisSolveWork? = null
        private set

    init {
        require(updateLimit > 0)
        require(fillFactor.isFinite() && fillFactor >= 1.0)
        require(densityThreshold.isFinite() && densityThreshold in 0.0..1.0)
    }

    override val nnz: Int
        get() {
            requireOpen()
            val current = cache ?: return 0
            return current.factors.lower.nnz + current.ft.upperEntries + current.ft.transformEntries
        }
    override val updateCount: Int
        get() {
            requireOpen()
            return cache?.ft?.updateCount ?: 0
        }
    override val rcond: Double
        get() {
            requireOpen()
            val current = cache ?: return 0.0
            var smallest = Double.POSITIVE_INFINITY
            var largest = 0.0
            for (j in 0 until n) {
                val pivot = abs(current.ft.upper.columns[j][j])
                smallest = min(smallest, pivot)
                largest = max(largest, pivot)
            }
            return if (n == 0) 1.0 else smallest / largest
        }

    override fun refactorize(basicIndex: IntArray): Boolean {
        requireOpen()
        require(basicIndex.size == n && basicIndex.all { it in 0 until source.cols })
        // Invalidate before a numerical attempt: even an exceptional build cannot expose stale factors.
        cache = null
        singular = true
        columns = IntArray(0)
        lastSolveWork = null
        val result = builder.build(basicIndex, policy)
        if (result !is LuBuildResult.Built) return false
        cache = BasisSolveCache(result.factors, densityThreshold)
        columns = basicIndex.copyOf()
        singular = false
        return true
    }

    override fun ftran(x: IndexedVector, expectedDensity: Double) = solve(x, expectedDensity, transpose = false)

    override fun btran(x: IndexedVector, expectedDensity: Double) = solve(x, expectedDensity, transpose = true)

    private fun solve(x: IndexedVector, expectedDensity: Double, transpose: Boolean) {
        requireOpen()
        val current = checkNotNull(cache) { "basis has no usable factors" }
        require(x.size == n)
        require(expectedDensity.isFinite() && expectedDensity in 0.0..1.0)
        lastSolveWork = null
        val symbolic = current.factors.symbolic
        val first: TriangularSolveWork
        val second: TriangularSolveWork
        val transformEntries: Long
        if (transpose) {
            work.load(x, symbolic.columnPosition)
            first = current.upperTranspose.solve(work, expectedDensity)
            transformEntries = current.ft.backward(work)
            second = current.lowerTranspose.solve(work, expectedDensity)
            work.write(x, symbolic.rowOrder)
        } else {
            work.load(x, symbolic.rowPosition)
            first = current.lower.solve(work, expectedDensity)
            transformEntries = current.ft.forward(work)
            second = current.upper.solve(work, expectedDensity)
            work.write(x, symbolic.columnOrder)
        }
        lastSolveWork = BasisSolveWork(first, second, transformEntries, x.count)
    }

    override fun update(pivotRow: Int, entering: Int, spike: IndexedVector, pivotEta: IndexedVector?): BasisUpdate {
        requireOpen()
        require(pivotRow in 0 until n && entering in 0 until source.cols)
        require(spike.size == n && (pivotEta == null || pivotEta.size == n))
        val current = cache ?: return BasisUpdate.SINGULAR
        val pivot = spike[pivotRow]
        if (!pivot.isFinite() || pivot == 0.0 || abs(pivot) < policy.absoluteTolerance) return BasisUpdate.SINGULAR
        var usable = (1.0 / pivot).isFinite()
        spike.forEachStored { _, value ->
            if (!value.isFinite()) usable = false
            if (value != 0.0) {
                val ratio = value / pivot
                if (!ratio.isFinite() || ratio == 0.0) usable = false
            }
        }
        if (!usable) return BasisUpdate.SINGULAR
        mapped.load(spike, current.factors.symbolic.columnPosition)
        val pivotLabel = current.factors.symbolic.columnPosition[pivotRow]
        if (!current.ft.update(pivotLabel, mapped, policy.absoluteTolerance)) return BasisUpdate.SINGULAR
        columns[pivotRow] = entering
        return if (current.ft.updateCount >= updateLimit || current.ft.fillAdvice(fillFactor)) {
            BasisUpdate.REFACTORIZE
        } else {
            BasisUpdate.APPLIED
        }
    }

    internal val lastUpdateWork: ForrestTomlinWork? get() = cache?.ft?.lastUpdateWork

    override fun solveQuality(rhs: DoubleArray, solution: IndexedVector, transpose: Boolean): BasisSolveQuality {
        requireOpen()
        checkNotNull(cache) { "basis has no usable factors" }
        require(rhs.size == n && solution.size == n)
        val product = DoubleArray(n)
        for (j in 0 until n) {
            source.forEachInColumn(columns[j]) { i, value ->
                if (transpose) product[j] += value * solution[i] else product[i] += value * solution[j]
            }
        }
        var residual = 0.0
        var scale = 1.0
        for (i in 0 until n) {
            if (!product[i].isFinite() || !rhs[i].isFinite() || !solution[i].isFinite()) {
                return BasisSolveQuality(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
            }
            residual = max(residual, abs(product[i] - rhs[i]))
            scale = max(scale, max(abs(product[i]), abs(rhs[i])))
        }
        return BasisSolveQuality(residual, residual / scale)
    }

    override fun close() {
        if (closed) return
        closed = true
        cache = null
        columns = IntArray(0)
        work.clear()
        mapped.clear()
        lastSolveWork = null
    }

    private fun requireOpen() = check(!closed) { "basis solver is closed" }
}

private class BasisSolveCache(val factors: LuFactors, threshold: Double) {
    val ft = ForrestTomlinFactors(factors)
    val lower = HyperSparseSolve(factors.lower, lower = true, unitDiagonal = true, threshold)
    val upper = HyperSparseSolve(ft.upper, lower = false, unitDiagonal = false, threshold)
    val lowerTranspose = HyperSparseSolve(factors.lowerTranspose, lower = false, unitDiagonal = true, threshold)
    val upperTranspose = HyperSparseSolve(ft.transpose, lower = true, unitDiagonal = false, threshold)
}
