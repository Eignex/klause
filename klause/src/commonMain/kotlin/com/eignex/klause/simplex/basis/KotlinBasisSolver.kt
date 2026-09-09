package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.sparse.basis.BasisSolveQuality
import com.eignex.koblas.sparse.basis.BasisSolver
import com.eignex.koblas.sparse.basis.BasisUpdate
import com.eignex.koblas.sparse.basis.IndexedVector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class BasisSolveWork(
    val first: TriangularSolveWork,
    val second: TriangularSolveWork,
    val etaEntries: Long,
    val outputSupport: Int,
)

// Single-threaded owner of source, factors, etas and scratch. Caller vectors never become retained buffers.
// Mandatory operations/reports reject close; n and the last refactorization's singular flag remain readable.
// Optional repair/snapshot behavior is exactly the seam default. No cancellation or resource API is exposed.
internal class KotlinBasisSolver(
    matrix: SparseMatrix,
    private val policy: LuPivotPolicy = LuPivotPolicy(),
    private val etaLimit: Int = 50,
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
    private val etas = mutableListOf<BasisEta>()
    private var columns = IntArray(0)
    private var closed = false
    override var singular = true
        private set
    var lastSolveWork: BasisSolveWork? = null
        private set

    init {
        require(etaLimit > 0)
        require(densityThreshold.isFinite() && densityThreshold in 0.0..1.0)
    }

    override val nnz: Int
        get() {
            requireOpen()
            val factors = cache?.factors ?: return 0
            return factors.lower.nnz + factors.upper.nnz + etas.sumOf { it.indices.size }
        }
    override val updateCount: Int
        get() {
            requireOpen()
            return etas.size
        }
    override val rcond: Double
        get() {
            requireOpen()
            val factors = cache?.factors ?: return 0.0
            var smallest = Double.POSITIVE_INFINITY
            var largest = 0.0
            for (j in 0 until n) {
                val pivot = abs(factors.upper[j, j])
                smallest = min(smallest, pivot)
                largest = max(largest, pivot)
            }
            for (eta in etas) {
                smallest = min(smallest, abs(eta.pivot))
                largest = max(largest, abs(eta.pivot))
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
        etas.clear()
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
        var etaEntries = 0L
        if (transpose) {
            work.load(x)
            // B = B0 E1 ... Ek; B^-T = B0^-T E1^-T ... Ek^-T.
            for (k in etas.size - 1 downTo 0) etaEntries += etas[k].transpose(work)
            permute(work, mapped, symbolic.columnPosition)
            first = current.upperTranspose.solve(mapped, expectedDensity)
            second = current.lowerTranspose.solve(mapped, expectedDensity)
            mapped.write(x, symbolic.rowOrder)
        } else {
            work.load(x, symbolic.rowPosition)
            first = current.lower.solve(work, expectedDensity)
            second = current.upper.solve(work, expectedDensity)
            permute(work, mapped, symbolic.columnOrder)
            for (eta in etas) etaEntries += eta.forward(mapped)
            mapped.write(x)
        }
        lastSolveWork = BasisSolveWork(first, second, etaEntries, x.count)
    }

    override fun update(pivotRow: Int, entering: Int, spike: IndexedVector, pivotEta: IndexedVector?): BasisUpdate {
        requireOpen()
        require(pivotRow in 0 until n && entering in 0 until source.cols)
        require(spike.size == n && (pivotEta == null || pivotEta.size == n))
        if (cache == null) return BasisUpdate.SINGULAR
        val pivot = spike[pivotRow]
        if (!pivot.isFinite() || pivot == 0.0 || abs(pivot) < policy.absoluteTolerance) return BasisUpdate.SINGULAR
        var usable = (1.0 / pivot).isFinite()
        var count = 0
        spike.forEachStored { _, value ->
            if (!value.isFinite()) usable = false
            if (value != 0.0) {
                val ratio = value / pivot
                if (!ratio.isFinite() || ratio == 0.0) usable = false
                count++
            }
        }
        if (!usable) return BasisUpdate.SINGULAR
        val indices = IntArray(count)
        val values = DoubleArray(count)
        var next = 0
        spike.forEachStored { i, value ->
            if (value != 0.0) {
                indices[next] = i
                values[next++] = value
            }
        }
        etas.add(BasisEta(pivotRow, pivot, indices, values))
        columns[pivotRow] = entering
        return if (etas.size >= etaLimit) BasisUpdate.REFACTORIZE else BasisUpdate.APPLIED
    }

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
        etas.clear()
        work.clear()
        mapped.clear()
        lastSolveWork = null
    }

    private fun requireOpen() = check(!closed) { "basis solver is closed" }

    private fun permute(from: BasisWorkspace, to: BasisWorkspace, position: IntArray) {
        to.clear()
        for (k in 0 until from.count) {
            val i = from.indices[k]
            if (from.values[i] != 0.0) to.set(position[i], from.values[i])
        }
    }
}

private class BasisSolveCache(val factors: LuFactors, threshold: Double) {
    val lower = HyperSparseSolve(factors.lower, lower = true, unitDiagonal = true, threshold)
    val upper = HyperSparseSolve(factors.upper, lower = false, unitDiagonal = false, threshold)
    val lowerTranspose = HyperSparseSolve(factors.lowerTranspose, lower = false, unitDiagonal = true, threshold)
    val upperTranspose = HyperSparseSolve(factors.upperTranspose, lower = true, unitDiagonal = false, threshold)
}

private class BasisEta(val row: Int, val pivot: Double, val indices: IntArray, val values: DoubleArray) {
    fun forward(work: BasisWorkspace): Long {
        if (work.values[row] == 0.0) return 0
        val value = basisQuotient(work.values[row], pivot)
        for (k in indices.indices) {
            val i = indices[k]
            if (i != row) work.set(i, basisFinite(work.values[i] - basisProduct(values[k], value)))
        }
        work.set(row, value)
        return indices.size.toLong()
    }

    fun transpose(work: BasisWorkspace): Long {
        var value = work.values[row]
        for (k in indices.indices) {
            val i = indices[k]
            if (i != row) value = basisFinite(value - basisProduct(values[k], work.values[i]))
        }
        work.set(row, basisQuotient(value, pivot))
        return indices.size.toLong()
    }
}
