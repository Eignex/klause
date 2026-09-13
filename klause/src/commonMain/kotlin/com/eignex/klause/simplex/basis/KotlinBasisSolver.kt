package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.Workspace
import com.eignex.koblas.borrow
import com.eignex.koblas.koblas
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
// Repair and snapshots stay within this fixed source identity; partial repair factors remain private.
internal class KotlinBasisSolver(
    matrix: SparseMatrix,
    private val policy: LuPivotPolicy = LuPivotPolicy(),
    private val updateLimit: Int = 50,
    private val fillFactor: Double = 5.0,
    private val densityThreshold: Double = 0.2,
    private val reusePivotOrder: Boolean = true,
) : BasisSolver {
    private val sourcePointers = matrix.copyColumnPointers()
    private val sourceRows = matrix.copyRowIndices()
    private val sourceValues = matrix.values.copyOf()
    private val source = SparseMatrix.wrap(
        matrix.rows,
        matrix.cols,
        sourcePointers,
        sourceRows,
        sourceValues,
    )
    private val workspace = Workspace()
    private val builder = BasisFactors(source, workspace)
    override val n = source.rows
    private val solveWorkspace = BasisWorkspace(n, workspace)
    private val mapped = BasisWorkspace(n, workspace)
    private val identity = Any()
    private val liveSnapshots = mutableSetOf<KotlinBasisSnapshot>()
    private var cache: BasisSolveCache? = null
    private var columns = IntArray(0)
    private var unitRows = IntArray(0)
    private var retainedOrder: SymbolicLu? = null
    private var closed = false
    private var repairActive = false
    override var singular = true
        private set
    var lastSolveWork: BasisSolveWork? = null
        private set
    private val workMeter = BasisWorkMeter()
    private val operationMeter = BasisOperationMeter()
    override val basisWork: BasisWork
        get() {
            requireOpen()
            return workMeter.snapshot()
        }
    override val basisOperationWork: BasisOperationWork
        get() {
            requireOpen()
            return operationMeter.snapshot()
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
        operationMeter.attempt(BasisOperationKind.REFACTORIZATION)
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
        val report = meter.report(
            result is LuBuildResult.Built,
            result.report.takeIf { result is LuBuildResult.Built },
        )
        workMeter.reset(report)
        if (result !is LuBuildResult.Built) {
            operationMeter.decline(BasisOperationKind.REFACTORIZATION, report.units)
            return false
        }
        operationMeter.success(BasisOperationKind.REFACTORIZATION, report.units)
        install(result, basicIndex, IntArray(n) { -1 })
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    override fun refactorizeRepairing(basicIndex: IntArray, control: BasisRepairControl): BasisRepair? {
        requireOpen()
        validateBasis(basicIndex)
        val requested = basicIndex.copyOf()
        val proposed = retainedOrder.takeIf { reusePivotOrder }
        invalidate()
        repairActive = true
        operationMeter.attempt(BasisOperationKind.REPAIR)
        val attempt = RepairAttempt(control, requested, proposed)
        var successful = false
        var primary: Throwable? = null
        var result: BasisRepair? = null
        try {
            result = attempt.run()
            successful = result != null
        } catch (failure: Throwable) {
            primary = failure
            control.charge(0, complete = false)
        } finally {
            try {
                attempt.close()
            } catch (cleanup: Throwable) {
                successful = false
                control.charge(0, complete = false)
                if (primary != null) primary.addSuppressed(cleanup) else primary = cleanup
            } finally {
                repairActive = false
                if (!successful) invalidate()
                workMeter.reset(if (successful) attempt.report else attempt.builds.report(false))
                when {
                    successful -> operationMeter.success(BasisOperationKind.REPAIR, attempt.repairUnits)
                    control.accountingComplete -> operationMeter.decline(BasisOperationKind.REPAIR, attempt.repairUnits)
                    else -> operationMeter.declineUnknown(BasisOperationKind.REPAIR, attempt.repairUnits)
                }
            }
        }
        primary?.let { throw it }
        return result
    }

    private inner class RepairAttempt(
        private val control: BasisRepairControl,
        private val requested: IntArray,
        private val proposed: SymbolicLu?,
    ) {
        val builds = BasisBuildAccumulator(BasisBuildKind.REPAIR)
        var report: BasisBuildWork? = null
            private set
        var repairUnits = 0L
            private set
        private var scratch: KotlinBasisSolver? = null

        fun run(): BasisRepair? {
            charge(requested.size.toLong())
            if (!control.check()) return null
            val owner = KotlinBasisSolver(source, policy, updateLimit, fillFactor, densityThreshold, reusePivotOrder)
            scratch = owner
            charge(2L * (sourcePointers.size.toLong() + sourceRows.size + sourceValues.size) + 6L * n + 2)
            if (!control.check()) return null
            val repairedColumns = IntArray(n) { -1 }
            val repairedUnits = IntArray(n) { it }
            charge(2L * n)
            val initial = build(owner, repairedColumns, repairedUnits, proposed) ?: return null
            owner.install(initial, repairedColumns, repairedUnits)
            charge(4L * n + cacheCopyUnits(initial.factors))
            val spike = IndexedVector(n)
            charge(3L * n)
            var unitsRemaining = n
            for (requestedSlot in requested.indices) {
                if (unitsRemaining == 0) break
                if (!control.check()) return null
                val entering = requested[requestedSlot]
                if (!scatter(spike, entering)) return null
                if (!control.check()) return null
                if (!solve(owner, spike)) return null
                val candidates = candidates(spike, repairedUnits, requestedSlot) ?: return null
                for (slot in candidates) {
                    if (!control.check()) return null
                    val update = update(owner, slot, entering, spike)
                    if (!control.check()) return null
                    if (update == BasisUpdate.SINGULAR) continue
                    repairedColumns[slot] = entering
                    repairedUnits[slot] = -1
                    unitsRemaining--
                    charge(2)
                    if (update == BasisUpdate.REFACTORIZE) {
                        val rebuilt = build(owner, repairedColumns, repairedUnits, owner.retainedOrder) ?: return null
                        owner.install(rebuilt, repairedColumns, repairedUnits)
                        charge(4L * n + cacheCopyUnits(rebuilt.factors))
                    }
                    break
                }
            }
            val final = build(
                this@KotlinBasisSolver,
                repairedColumns,
                repairedUnits,
                owner.retainedOrder,
            ) ?: return null
            val result = BasisRepair(repairedColumns, repairedUnits)
            val installed = BasisSolveCache(final.factors, densityThreshold, workspace)
            val installedColumns = repairedColumns.copyOf()
            val installedUnits = repairedUnits.copyOf()
            val installedOrder = SymbolicLu(
                final.factors.symbolic.rowOrder.copyOf(),
                final.factors.symbolic.columnOrder.copyOf(),
            )
            charge(6L * n + cacheCopyUnits(final.factors))
            close()
            report = builds.report(true, final.report)
            if (!control.check()) {
                report = builds.report(false)
                return null
            }
            cache = installed
            columns = installedColumns
            unitRows = installedUnits
            retainedOrder = installedOrder
            singular = false
            return result
        }

        private fun scatter(spike: IndexedVector, entering: Int): Boolean {
            val start = sourcePointers[entering]
            val end = sourcePointers[entering + 1]
            for (at in start until end) {
                if (at % 128 == 0 && !control.check()) return false
                charge(1)
                if (!sourceValues[at].isFinite()) return false
            }
            if (!control.check()) return false
            val previous = spike.count
            spike.scatterColumn(source, entering)
            charge(previous.toLong() + end - start)
            return control.check()
        }

        private fun cacheCopyUnits(factors: LuFactors): Long =
            4L * (n.toLong() + 1) + 2L * (factors.lower.nnz.toLong() + factors.upper.nnz) + 37L * n + 3

        private fun build(
            owner: KotlinBasisSolver,
            columns: IntArray,
            units: IntArray,
            order: SymbolicLu?,
        ): LuBuildResult.Built? {
            if (!control.check()) return null
            val result = owner.builder.build(columns, units, policy, order.takeIf { reusePivotOrder })
            builds.add(result.report)
            charge(result.report.units)
            if (!control.check()) return null
            return result as? LuBuildResult.Built
        }

        private fun candidates(spike: IndexedVector, units: IntArray, requestedSlot: Int): List<Int>? {
            val candidates = mutableListOf<Int>()
            var maximum = 0.0
            for (slot in 0 until n) {
                if (slot % 128 == 0 && !control.check()) return null
                val value = abs(spike[slot])
                charge(1)
                if (!value.isFinite()) return null
                if (units[slot] >= 0 && value > 0.0 && value >= policy.absoluteTolerance) {
                    candidates.add(slot)
                    maximum = max(maximum, value)
                }
            }
            if (!control.check()) return null
            var comparisons = 0
            try {
                candidates.sortWith { left, right ->
                    charge(1)
                    if (++comparisons % 128 == 0 && !control.check()) throw RepairStopped()
                    val magnitude = abs(spike[right]).compareTo(abs(spike[left]))
                    if (magnitude != 0) {
                        magnitude
                    } else {
                        ((left - requestedSlot + n) % n).compareTo((right - requestedSlot + n) % n)
                    }
                }
            } catch (_: RepairStopped) {
                return null
            }
            var retained = 0
            for (slot in candidates) {
                if (retained % 128 == 0 && !control.check()) return null
                charge(1)
                if (abs(spike[slot]) < policy.relativeThreshold * maximum) break
                retained++
            }
            return candidates.subList(0, retained)
        }

        @Suppress("TooGenericExceptionCaught")
        private fun solve(owner: KotlinBasisSolver, spike: IndexedVector): Boolean {
            operationMeter.attempt(BasisOperationKind.FTRAN)
            val before = owner.basisOperationWork
            var successful = false
            try {
                owner.ftran(spike, 0.0)
                successful = true
            } finally {
                recordKernel(before, owner.basisOperationWork, BasisOperationKind.FTRAN, successful)
            }
            return control.check()
        }

        private fun update(owner: KotlinBasisSolver, slot: Int, entering: Int, spike: IndexedVector): BasisUpdate {
            operationMeter.attempt(BasisOperationKind.UPDATE)
            val before = owner.basisOperationWork
            var successful = false
            try {
                return owner.update(slot, entering, spike, spike).also { successful = it != BasisUpdate.SINGULAR }
            } finally {
                recordKernel(before, owner.basisOperationWork, BasisOperationKind.UPDATE, successful)
            }
        }

        private fun recordKernel(
            before: BasisOperationWork,
            after: BasisOperationWork,
            kind: BasisOperationKind,
            successful: Boolean,
        ) {
            val monotonic = after.units >= before.units
            val units = if (monotonic) after.units - before.units else 0
            val complete = monotonic && !before.saturated && !after.saturated && before.complete && after.complete
            control.chargeReported(units, complete)
            when {
                successful && complete -> operationMeter.success(kind, units)
                complete -> operationMeter.decline(kind, units)
                else -> operationMeter.declineUnknown(kind, units)
            }
        }

        private fun charge(units: Long) {
            repairUnits = saturatedAdd(repairUnits, units)
            control.chargeReported(units)
        }

        fun close() {
            val owner = scratch ?: return
            scratch = null
            val entries = owner.solveWorkspace.count.toLong() + owner.mapped.count
            owner.close()
            charge(entries)
        }
    }

    private class RepairStopped : RuntimeException()

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
                operationMeter.declineUnknown(
                    if (transpose) BasisOperationKind.BTRAN else BasisOperationKind.FTRAN,
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
        operationMeter.attempt(BasisOperationKind.UPDATE)
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
        val updated = try {
            current.ft.update(pivotLabel, mapped, policy.absoluteTolerance)
        } catch (failure: BasisArithmeticException) {
            val units = saturatedAdd(validationUnits, current.ft.lastUpdateWork?.units ?: 0)
            workMeter.updateDecline(units)
            operationMeter.declineUnknown(BasisOperationKind.UPDATE, units)
            throw failure
        }
        if (!updated) {
            val attemptedUnits = current.ft.lastUpdateWork?.units ?: 0
            return declineUpdate(saturatedAdd(validationUnits, attemptedUnits))
        }
        columns[pivotRow] = entering
        unitRows[pivotRow] = -1
        val updateUnits = saturatedAdd(validationUnits, checkNotNull(current.ft.lastUpdateWork).units)
        workMeter.updateSuccess(updateUnits)
        operationMeter.success(BasisOperationKind.UPDATE, updateUnits)
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
        return workspace.borrow(n) { product ->
            product.fill(0.0)
            for (j in 0 until n) {
                val unitRow = unitRows[j]
                if (unitRow >= 0) {
                    if (transpose) product[j] = solution[unitRow] else product[unitRow] += solution[j]
                } else {
                    val column = columns[j]
                    val start = sourcePointers[column]
                    val count = sourcePointers[column + 1] - start
                    if (transpose) {
                        product[j] = solution.dot(sourceRows, start, sourceValues, start, count)
                    } else {
                        koblas.sparseKernels.axpy(
                            product,
                            solution[j],
                            sourceRows,
                            start,
                            sourceValues,
                            start,
                            count,
                        )
                    }
                }
            }
            var residual = 0.0
            var scale = 1.0
            for (i in 0 until n) {
                if (!product[i].isFinite() || !rhs[i].isFinite() || !solution[i].isFinite()) {
                    return@borrow BasisSolveQuality(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
                }
                residual = max(residual, abs(product[i] - rhs[i]))
                scale = max(scale, max(abs(product[i]), abs(rhs[i])))
            }
            BasisSolveQuality(residual, residual / scale)
        }
    }

    override fun snapshot(): BasisSnapshot? {
        requireOpen()
        operationMeter.attempt(BasisOperationKind.SNAPSHOT)
        val current = cache
        if (current == null || singular) {
            operationMeter.decline(BasisOperationKind.SNAPSHOT, 0)
            return null
        }
        val copied = current.snapshot()
        val units = snapshotCopyUnits(copied)
        return KotlinBasisSnapshot(
            identity,
            n,
            source.cols,
            policy,
            updateLimit,
            fillFactor,
            densityThreshold,
            reusePivotOrder,
            copied,
            columns.copyOf(),
            unitRows.copyOf(),
            lastSolveWork,
            workMeter.snapshot(),
        ).also {
            liveSnapshots.add(it)
            operationMeter.success(BasisOperationKind.SNAPSHOT, units)
        }
    }

    override fun ordering(): BasisOrdering? {
        check(!repairActive) { "basis repair is active" }
        if (closed || singular) return null
        val current = cache ?: return null
        // FT transforms change the source elimination problem; their triangular labels are not LU pivots.
        if (current.ft.updateCount != 0 || !columns.contentEquals(current.factors.basisColumns) ||
            !unitRows.contentEquals(current.factors.basisUnitRows)
        ) {
            return null
        }
        return BasisOrdering(
            columns,
            unitRows,
            current.factors.symbolic.rowOrder,
            current.factors.symbolic.columnOrder,
        )
    }

    override fun restore(snapshot: BasisSnapshot): Boolean {
        requireOpen()
        operationMeter.attempt(BasisOperationKind.RESTORE)
        val own = snapshot as? KotlinBasisSnapshot
        val state = own?.state
        if (own == null || state == null) {
            operationMeter.decline(BasisOperationKind.RESTORE, 1)
            return false
        }
        if (
            own.owner !== identity || own !in liveSnapshots || own.dimension != n ||
            own.sourceColumns != source.cols || own.policy != policy || own.updateLimit != updateLimit ||
            own.fillFactor != fillFactor || own.densityThreshold != densityThreshold ||
            own.reusePivotOrder != reusePivotOrder
        ) {
            operationMeter.decline(BasisOperationKind.RESTORE, 8)
            return false
        }
        val units = own.restoreCopyUnits
        val restored = try {
            BasisSolveCache.restore(state, densityThreshold, workspace)
        } catch (failure: BasisArithmeticException) {
            operationMeter.declineUnknown(BasisOperationKind.RESTORE, units)
            throw failure
        }
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
        operationMeter.success(BasisOperationKind.RESTORE, units)
        return true
    }

    override fun extend(matrix: SparseMatrix, extension: BasisExtension): BasisExtensionResult? {
        requireOpen()
        operationMeter.attempt(BasisOperationKind.EXTENSION)
        val verification = inspectBasisExtension(source, matrix, columns, unitRows, extension)
        val state = verification.state
        val current = cache
        if (state == null || current == null) {
            operationMeter.decline(BasisOperationKind.EXTENSION, verification.units)
            return null
        }
        val target = KotlinBasisSolver(
            matrix,
            policy,
            updateLimit,
            fillFactor,
            densityThreshold,
            reusePivotOrder,
        )
        var transferred = false
        try {
            val (extended, units) = buildExtendedCache(current, source, state, densityThreshold, target.workspace)
            target.installExtension(extended, state.basisColumns, state.basisUnitRows, units)
            operationMeter.success(
                BasisOperationKind.EXTENSION,
                units,
            )
            transferred = true
            return BasisExtensionResult(target, state.basisColumns, state.basisUnitRows)
        } catch (failure: BasisArithmeticException) {
            operationMeter.declineUnknown(BasisOperationKind.EXTENSION, verification.units)
            throw failure
        } finally {
            if (!transferred) target.close()
        }
    }

    override fun close() {
        check(!repairActive) { "basis repair is active" }
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
        cache = BasisSolveCache(result.factors, densityThreshold, workspace)
        this.columns = columns.copyOf()
        this.unitRows = unitRows.copyOf()
        retainedOrder = SymbolicLu(
            result.factors.symbolic.rowOrder.copyOf(),
            result.factors.symbolic.columnOrder.copyOf(),
        )
        singular = false
    }

    private fun installExtension(extended: BasisSolveCache, columns: IntArray, unitRows: IntArray, units: Long) {
        cache = extended
        this.columns = columns.copyOf()
        this.unitRows = unitRows.copyOf()
        retainedOrder = SymbolicLu(
            extended.factors.symbolic.rowOrder.copyOf(),
            extended.factors.symbolic.columnOrder.copyOf(),
        )
        workMeter.reset(
            BasisBuildWork(
                BasisBuildKind.EXTENSION,
                successful = true,
                builds = 0,
                orderingAttempts = 0,
                reusedOrders = 0,
                fallbacks = 0,
                units = units,
                installedBuildUnits = null,
            ),
        )
        singular = false
    }

    private fun recordSolveAttempt(transpose: Boolean) {
        workMeter.solveAttempt(transpose)
        operationMeter.attempt(if (transpose) BasisOperationKind.BTRAN else BasisOperationKind.FTRAN)
    }

    private fun recordSolveSuccess(transpose: Boolean, units: Long) {
        workMeter.solveSuccess(transpose, units)
        operationMeter.success(if (transpose) BasisOperationKind.BTRAN else BasisOperationKind.FTRAN, units)
    }

    private fun declineUpdate(units: Long): BasisUpdate {
        workMeter.updateDecline(units)
        operationMeter.decline(BasisOperationKind.UPDATE, units)
        return BasisUpdate.SINGULAR
    }

    private fun snapshotCopyUnits(state: BasisCacheState): Long {
        val factors = state.factors
        val matrices = listOf(factors.lower, factors.upper, factors.lowerTranspose, factors.upperTranspose)
        val factorEntries = matrices.fold(0L) { total, matrix ->
            saturatedAdd(total, matrix.cols + 1L + matrix.nnz.toLong() * 2L)
        }
        val triangularEntries = (state.ft.upper.asSequence() + state.ft.transpose.asSequence()).fold(0L) {
                total,
                slice,
            ->
            saturatedAdd(total, slice.count.toLong() * 2L)
        }
        val ftEntries = saturatedAdd(
            triangularEntries,
            saturatedAdd(state.ft.order.size.toLong(), state.ft.transformEntries.toLong() * 2L),
        )
        return saturatedAdd(
            factorEntries,
            saturatedAdd(ftEntries, n.toLong() * 6L),
        )
    }

    private fun requireOpen() {
        check(!closed) { "basis solver is closed" }
        check(!repairActive) { "basis repair is active" }
    }

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
        val restoreCopyUnits: Long = saturatedAdd(snapshotCopyUnits(state), dimension.toLong() * 4L),
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

internal class BasisSolveCache private constructor(
    val factors: LuFactors,
    val ft: ForrestTomlinFactors,
    threshold: Double,
) {
    constructor(factors: LuFactors, threshold: Double, workspace: Workspace) :
        this(factors, ForrestTomlinFactors(factors, workspace), threshold)

    val lower = HyperSparseSolve(factors.lower, lower = true, unitDiagonal = true, threshold)
    val upper = HyperSparseSolve(ft.upper, lower = false, unitDiagonal = false, threshold)
    val lowerTranspose = HyperSparseSolve(factors.lowerTranspose, lower = false, unitDiagonal = true, threshold)
    val upperTranspose = HyperSparseSolve(ft.transpose, lower = true, unitDiagonal = false, threshold)

    fun snapshot() = BasisCacheState(factors.copyOwned(), ft.snapshot())

    companion object {
        fun transfer(
            factors: LuFactors,
            state: ForrestTomlinState,
            threshold: Double,
            workspace: Workspace,
        ): BasisSolveCache = BasisSolveCache(
            factors,
            ForrestTomlinFactors.transfer(factors, state, workspace),
            threshold,
        )

        fun restore(state: BasisCacheState, threshold: Double, workspace: Workspace): BasisSolveCache {
            val factors = state.factors.copyOwned()
            return BasisSolveCache(factors, ForrestTomlinFactors.restore(factors, state.ft, workspace), threshold)
        }
    }
}

internal class BasisCacheState(val factors: LuFactors, val ft: ForrestTomlinState)
