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
// Repair and snapshots stay within this fixed source identity. No cancellation or resource API is exposed.
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
    private val identity = Any()
    private val liveSnapshots = mutableSetOf<KotlinBasisSnapshot>()
    private var cache: BasisSolveCache? = null
    private var columns = IntArray(0)
    private var unitRows = IntArray(0)
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
        validateBasis(basicIndex)
        // Invalidate before a numerical attempt: even an exceptional build cannot expose stale factors.
        invalidate()
        val result = builder.build(basicIndex, policy)
        if (result !is LuBuildResult.Built) return false
        install(result, basicIndex, IntArray(n) { -1 })
        return true
    }

    override fun refactorizeRepairing(basicIndex: IntArray): BasisRepair? {
        requireOpen()
        validateBasis(basicIndex)
        invalidate()
        val repairedColumns = IntArray(n) { -1 }
        val repairedUnits = IntArray(n) { it }
        var accepted = builder.build(repairedColumns, repairedUnits, policy) as? LuBuildResult.Built ?: return null
        for (requestedSlot in basicIndex.indices) {
            val entering = basicIndex[requestedSlot]
            for (offset in repairedUnits.indices) {
                val slot = (requestedSlot + offset) % n
                if (repairedUnits[slot] < 0) continue
                val trialColumns = repairedColumns.copyOf()
                val trialUnits = repairedUnits.copyOf()
                trialColumns[slot] = entering
                trialUnits[slot] = -1
                val trial = builder.build(trialColumns, trialUnits, policy)
                if (trial is LuBuildResult.Built) {
                    trialColumns.copyInto(repairedColumns)
                    trialUnits.copyInto(repairedUnits)
                    accepted = trial
                    break
                }
            }
        }
        install(accepted, repairedColumns, repairedUnits)
        return BasisRepair(repairedColumns, repairedUnits)
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
        unitRows[pivotRow] = -1
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
            val unitRow = unitRows[j]
            if (unitRow >= 0) {
                if (transpose) product[j] += solution[unitRow] else product[unitRow] += solution[j]
            } else {
                source.forEachInColumn(columns[j]) { i, value ->
                    if (transpose) product[j] += value * solution[i] else product[i] += value * solution[j]
                }
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

    override fun snapshot(): BasisSnapshot? {
        requireOpen()
        val current = cache ?: return null
        if (singular) return null
        return KotlinBasisSnapshot(
            identity,
            n,
            source.cols,
            policy,
            updateLimit,
            fillFactor,
            densityThreshold,
            current.snapshot(),
            columns.copyOf(),
            unitRows.copyOf(),
            lastSolveWork,
        ).also { liveSnapshots.add(it) }
    }

    override fun restore(snapshot: BasisSnapshot): Boolean {
        requireOpen()
        val own = snapshot as? KotlinBasisSnapshot ?: return false
        val state = own.state ?: return false
        if (
            own.owner !== identity || own !in liveSnapshots || own.dimension != n ||
            own.sourceColumns != source.cols || own.policy != policy || own.updateLimit != updateLimit ||
            own.fillFactor != fillFactor || own.densityThreshold != densityThreshold
        ) {
            return false
        }
        val restored = BasisSolveCache.restore(state, densityThreshold)
        cache = restored
        columns = own.columns.copyOf()
        unitRows = own.unitRows.copyOf()
        singular = false
        lastSolveWork = own.lastSolveWork
        work.clear()
        mapped.clear()
        return true
    }

    override fun close() {
        if (closed) return
        for (snapshot in liveSnapshots.toList()) snapshot.close()
        closed = true
        cache = null
        columns = IntArray(0)
        unitRows = IntArray(0)
        work.clear()
        mapped.clear()
        lastSolveWork = null
    }

    private fun validateBasis(basicIndex: IntArray) =
        require(basicIndex.size == n && basicIndex.all { it in 0 until source.cols })

    private fun invalidate() {
        cache = null
        singular = true
        columns = IntArray(0)
        unitRows = IntArray(0)
        lastSolveWork = null
    }

    private fun install(result: LuBuildResult.Built, columns: IntArray, unitRows: IntArray) {
        cache = BasisSolveCache(result.factors, densityThreshold)
        this.columns = columns.copyOf()
        this.unitRows = unitRows.copyOf()
        singular = false
    }

    private fun requireOpen() = check(!closed) { "basis solver is closed" }

    private inner class KotlinBasisSnapshot(
        val owner: Any,
        val dimension: Int,
        val sourceColumns: Int,
        val policy: LuPivotPolicy,
        val updateLimit: Int,
        val fillFactor: Double,
        val densityThreshold: Double,
        state: BasisCacheState,
        columns: IntArray,
        unitRows: IntArray,
        val lastSolveWork: BasisSolveWork?,
    ) : BasisSnapshot {
        var state: BasisCacheState? = state
            private set
        var columns = columns
            private set
        var unitRows = unitRows
            private set

        override fun close() {
            if (state == null) return
            state = null
            columns = IntArray(0)
            unitRows = IntArray(0)
            liveSnapshots.remove(this)
        }
    }
}

private class BasisSolveCache private constructor(
    val factors: LuFactors,
    val ft: ForrestTomlinFactors,
    threshold: Double,
) {
    constructor(factors: LuFactors, threshold: Double) : this(factors, ForrestTomlinFactors(factors), threshold)

    val lower = HyperSparseSolve(factors.lower, lower = true, unitDiagonal = true, threshold)
    val upper = HyperSparseSolve(ft.upper, lower = false, unitDiagonal = false, threshold)
    val lowerTranspose = HyperSparseSolve(factors.lowerTranspose, lower = false, unitDiagonal = true, threshold)
    val upperTranspose = HyperSparseSolve(ft.transpose, lower = true, unitDiagonal = false, threshold)

    fun snapshot() = BasisCacheState(factors.copyOwned(), ft.snapshot())

    companion object {
        fun restore(state: BasisCacheState, threshold: Double): BasisSolveCache {
            val factors = state.factors.copyOwned()
            return BasisSolveCache(factors, ForrestTomlinFactors.restore(factors, state.ft), threshold)
        }
    }
}

private class BasisCacheState(val factors: LuFactors, val ft: ForrestTomlinState)
