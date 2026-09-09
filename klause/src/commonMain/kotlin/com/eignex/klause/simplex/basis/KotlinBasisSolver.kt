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
    private val reusePivotOrder: Boolean = true,
) : BasisSolver {
    private val source = SparseMatrix.wrap(
        matrix.rows,
        matrix.cols,
        matrix.copyColumnPointers(),
        matrix.copyRowIndices(),
        matrix.values.copyOf(),
    )
    private val builder = BasisFactors(source)
    override val n = source.rows
    private val solveWorkspace = BasisWorkspace(n)
    private val mapped = BasisWorkspace(n)
    private val identity = Any()
    private val liveSnapshots = mutableSetOf<KotlinBasisSnapshot>()
    private var cache: BasisSolveCache? = null
    private var columns = IntArray(0)
    private var unitRows = IntArray(0)
    private var retainedOrder: SymbolicLu? = null
    private var closed = false
    override var singular = true
        private set
    var lastSolveWork: BasisSolveWork? = null
        private set
    private val workMeter = BasisWorkMeter()
    override val basisWork: BasisWork
        get() {
            requireOpen()
            return workMeter.snapshot()
        }

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
        val proposedOrder = retainedOrder.takeIf { reusePivotOrder }
        // Invalidate before a numerical attempt: even an exceptional build cannot expose stale factors.
        invalidate()
        val meter = BasisBuildAccumulator(BasisBuildKind.REFACTORIZATION)
        val result = builder.build(
            basicIndex,
            IntArray(n) { -1 },
            policy,
            proposedOrder,
        )
        meter.add(result.report)
        workMeter.reset(
            meter.report(
                result is LuBuildResult.Built,
                result.report.takeIf { result is LuBuildResult.Built },
            ),
        )
        if (result !is LuBuildResult.Built) return false
        install(result, basicIndex, IntArray(n) { -1 })
        return true
    }

    override fun refactorizeRepairing(basicIndex: IntArray): BasisRepair? {
        requireOpen()
        validateBasis(basicIndex)
        var proposedOrder = retainedOrder.takeIf { reusePivotOrder }
        invalidate()
        val meter = BasisBuildAccumulator(BasisBuildKind.REPAIR)
        val repairedColumns = IntArray(n) { -1 }
        val repairedUnits = IntArray(n) { it }
        val initial = builder.build(repairedColumns, repairedUnits, policy, proposedOrder)
        meter.add(initial.report)
        if (initial !is LuBuildResult.Built) {
            workMeter.reset(meter.report(false))
            return null
        }
        var accepted: LuBuildResult.Built = initial
        var installedReport = accepted.report
        proposedOrder = accepted.factors.symbolic.takeIf { reusePivotOrder }
        for (requestedSlot in basicIndex.indices) {
            val entering = basicIndex[requestedSlot]
            for (offset in repairedUnits.indices) {
                val slot = (requestedSlot + offset) % n
                if (repairedUnits[slot] < 0) continue
                val trialColumns = repairedColumns.copyOf()
                val trialUnits = repairedUnits.copyOf()
                trialColumns[slot] = entering
                trialUnits[slot] = -1
                val trial = builder.build(trialColumns, trialUnits, policy, proposedOrder)
                meter.add(trial.report)
                if (trial is LuBuildResult.Built) {
                    trialColumns.copyInto(repairedColumns)
                    trialUnits.copyInto(repairedUnits)
                    accepted = trial
                    installedReport = trial.report
                    proposedOrder = trial.factors.symbolic.takeIf { reusePivotOrder }
                    break
                }
            }
        }
        workMeter.reset(meter.report(true, installedReport))
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
        recordSolveAttempt(transpose)
        var successful = false
        var completedUnits = 0L
        var activeSolve: HyperSparseSolve? = null
        var transformStarted = false
        try {
            val symbolic = current.factors.symbolic
            val first: TriangularSolveWork
            val second: TriangularSolveWork
            val transformEntries: Long
            if (transpose) {
                solveWorkspace.load(x, symbolic.columnPosition)
                activeSolve = current.upperTranspose
                first = checkNotNull(activeSolve).solve(solveWorkspace, expectedDensity)
                completedUnits = saturatedAdd(completedUnits, first.units)
                activeSolve = null
                transformStarted = true
                transformEntries = current.ft.backward(solveWorkspace)
                completedUnits = saturatedAdd(completedUnits, transformEntries)
                transformStarted = false
                activeSolve = current.lowerTranspose
                second = checkNotNull(activeSolve).solve(solveWorkspace, expectedDensity)
                completedUnits = saturatedAdd(completedUnits, second.units)
                activeSolve = null
                solveWorkspace.write(x, symbolic.rowOrder)
            } else {
                solveWorkspace.load(x, symbolic.rowPosition)
                activeSolve = current.lower
                first = checkNotNull(activeSolve).solve(solveWorkspace, expectedDensity)
                completedUnits = saturatedAdd(completedUnits, first.units)
                activeSolve = null
                transformStarted = true
                transformEntries = current.ft.forward(solveWorkspace)
                completedUnits = saturatedAdd(completedUnits, transformEntries)
                transformStarted = false
                activeSolve = current.upper
                second = checkNotNull(activeSolve).solve(solveWorkspace, expectedDensity)
                completedUnits = saturatedAdd(completedUnits, second.units)
                activeSolve = null
                solveWorkspace.write(x, symbolic.columnOrder)
            }
            lastSolveWork = BasisSolveWork(first, second, transformEntries, x.count)
            recordSolveSuccess(transpose, checkNotNull(lastSolveWork).units)
            successful = true
        } finally {
            if (!successful) {
                val triangularUnits = activeSolve?.lastWork?.units ?: 0
                val transformUnits = if (transformStarted) current.ft.lastSolveEntries else 0
                workMeter.solveDecline(
                    transpose,
                    saturatedAdd(completedUnits, saturatedAdd(triangularUnits, transformUnits)),
                )
            }
        }
    }

    override fun update(pivotRow: Int, entering: Int, spike: IndexedVector, pivotEta: IndexedVector?): BasisUpdate {
        requireOpen()
        require(pivotRow in 0 until n && entering in 0 until source.cols)
        require(spike.size == n && (pivotEta == null || pivotEta.size == n))
        workMeter.updateAttempt()
        val current = cache ?: return declineUpdate(1)
        val pivot = spike[pivotRow]
        if (!pivot.isFinite() || pivot == 0.0 || abs(pivot) < policy.absoluteTolerance) return declineUpdate(1)
        var usable = (1.0 / pivot).isFinite()
        spike.forEachStored { _, value ->
            if (!value.isFinite()) usable = false
            if (value != 0.0) {
                val ratio = value / pivot
                if (!ratio.isFinite() || ratio == 0.0) usable = false
            }
        }
        val validationUnits = saturatedAdd(1, spike.count.toLong())
        if (!usable) return declineUpdate(validationUnits)
        mapped.load(spike, current.factors.symbolic.columnPosition)
        val pivotLabel = current.factors.symbolic.columnPosition[pivotRow]
        if (!current.ft.update(pivotLabel, mapped, policy.absoluteTolerance)) {
            val attemptedUnits = current.ft.lastUpdateWork?.units ?: 0
            return declineUpdate(saturatedAdd(validationUnits, attemptedUnits))
        }
        columns[pivotRow] = entering
        unitRows[pivotRow] = -1
        val updateUnits = saturatedAdd(validationUnits, checkNotNull(current.ft.lastUpdateWork).units)
        workMeter.updateSuccess(updateUnits)
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
            reusePivotOrder,
            current.snapshot(),
            columns.copyOf(),
            unitRows.copyOf(),
            lastSolveWork,
            workMeter.snapshot(),
        ).also { liveSnapshots.add(it) }
    }

    override fun restore(snapshot: BasisSnapshot): Boolean {
        requireOpen()
        val own = snapshot as? KotlinBasisSnapshot ?: return false
        val state = own.state ?: return false
        if (
            own.owner !== identity || own !in liveSnapshots || own.dimension != n ||
            own.sourceColumns != source.cols || own.policy != policy || own.updateLimit != updateLimit ||
            own.fillFactor != fillFactor || own.densityThreshold != densityThreshold ||
            own.reusePivotOrder != reusePivotOrder
        ) {
            return false
        }
        val restored = BasisSolveCache.restore(state, densityThreshold)
        cache = restored
        columns = own.columns.copyOf()
        unitRows = own.unitRows.copyOf()
        singular = false
        lastSolveWork = own.lastSolveWork
        workMeter.restore(own.work)
        retainedOrder = SymbolicLu(
            restored.factors.symbolic.rowOrder.copyOf(),
            restored.factors.symbolic.columnOrder.copyOf(),
        )
        solveWorkspace.clear()
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
        retainedOrder = null
        solveWorkspace.clear()
        mapped.clear()
        lastSolveWork = null
        workMeter.reset(null)
    }

    private fun validateBasis(basicIndex: IntArray) =
        require(basicIndex.size == n && basicIndex.all { it in 0 until source.cols })

    private fun invalidate() {
        cache = null
        singular = true
        columns = IntArray(0)
        unitRows = IntArray(0)
        lastSolveWork = null
        workMeter.reset(null)
    }

    private fun install(result: LuBuildResult.Built, columns: IntArray, unitRows: IntArray) {
        cache = BasisSolveCache(result.factors, densityThreshold)
        this.columns = columns.copyOf()
        this.unitRows = unitRows.copyOf()
        retainedOrder = SymbolicLu(
            result.factors.symbolic.rowOrder.copyOf(),
            result.factors.symbolic.columnOrder.copyOf(),
        )
        singular = false
    }

    private fun recordSolveAttempt(transpose: Boolean) {
        workMeter.solveAttempt(transpose)
    }

    private fun recordSolveSuccess(transpose: Boolean, units: Long) {
        workMeter.solveSuccess(transpose, units)
    }

    private fun declineUpdate(units: Long): BasisUpdate {
        workMeter.updateDecline(units)
        return BasisUpdate.SINGULAR
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
        val reusePivotOrder: Boolean,
        state: BasisCacheState,
        columns: IntArray,
        unitRows: IntArray,
        val lastSolveWork: BasisSolveWork?,
        val work: BasisWork,
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
