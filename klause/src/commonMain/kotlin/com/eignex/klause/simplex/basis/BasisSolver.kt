package com.eignex.klause.simplex.basis

import com.eignex.klause.util.Cancellation
import com.eignex.koblas.SparseMatrix

internal enum class BasisRepairStop {
    CANCELLED,
    WORK,
    UNKNOWN_WORK,
}

internal class BasisRepairControl(
    private val cancellation: Cancellation = Cancellation.Never,
    val maxWork: Long? = null,
) {
    var spentWork: Long = 0
        private set
    var accountingComplete: Boolean = true
        private set
    var stop: BasisRepairStop? = null
        private set
    var callbackFailure: Throwable? = null
        private set
    private var reportedWork = 0L

    init {
        require(maxWork == null || maxWork >= 0)
    }

    @Suppress("TooGenericExceptionCaught")
    fun check(): Boolean {
        callbackFailure?.let { throw it }
        if (stop != null) return false
        val cancelled = try {
            cancellation()
        } catch (failure: Throwable) {
            callbackFailure = failure
            throw failure
        }
        if (cancelled) stop = BasisRepairStop.CANCELLED
        updateStop()
        return stop == null
    }

    fun charge(units: Long, complete: Boolean = true) {
        spentWork = saturatedAdd(spentWork, units)
        accountingComplete = accountingComplete && complete
        updateStop()
    }

    // These units also appear in the measured owner's lifetime report; ordinary charges do not.
    fun chargeReported(units: Long, complete: Boolean = true) {
        reportedWork = saturatedAdd(reportedWork, units)
        charge(units, complete)
    }

    fun <T> measure(solver: BasisSolver, operation: () -> T): T {
        val before = workOf(solver)
        val reportedBefore = reportedWork
        var finished = false
        try {
            return operation().also { finished = true }
        } finally {
            val after = workOf(solver)
            val monotonic = before != null && after != null && after.units >= before.units
            val delta = if (monotonic) checkNotNull(after).units - checkNotNull(before).units else 0
            val reported = reportedWork - reportedBefore
            val complete = monotonic && before.complete && after.complete && !before.saturated &&
                !after.saturated && reportedWork != Long.MAX_VALUE && finished
            chargeReported(maxOf(0, delta - reported), complete)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun workOf(solver: BasisSolver): BasisOperationWork? = try {
        solver.basisOperationWork
    } catch (_: Throwable) {
        null
    }

    private fun updateStop() {
        if (stop != null || maxWork == null) return
        stop = when {
            !accountingComplete -> BasisRepairStop.UNKNOWN_WORK
            spentWork >= maxWork -> BasisRepairStop.WORK
            else -> null
        }
    }
}

internal class BasisArithmeticException(message: String) : ArithmeticException(message)

internal enum class BasisUpdate {
    APPLIED,
    REFACTORIZE,
    SINGULAR,
}

internal enum class RefactorizeReason {
    FACTOR_ASKED,
    UPDATES_WORN,
}

internal data class BasisKernel(val dimension: Int, val entries: Int)
internal data class BasisSolveQuality(val residualInfinityNorm: Double, val relativeResidual: Double)
internal class BasisRepair(columns: IntArray, unitRows: IntArray) {
    val columns = columns.copyOf()
    val unitRows = unitRows.copyOf()
    val repaired: Boolean get() = unitRows.any { it >= 0 }

    init {
        require(columns.size == unitRows.size)
        for (slot in columns.indices) {
            require(columns[slot] == -1 || columns[slot] >= 0)
            require(unitRows[slot] == -1 || unitRows[slot] in unitRows.indices)
            require((columns[slot] >= 0) != (unitRows[slot] >= 0))
        }
    }
}

internal interface BasisSnapshot : AutoCloseable

// Pivot positions map to source rows and slots of the accepted ordered basis, never inverse headings.
internal class BasisOrdering(columns: IntArray, unitRows: IntArray, rows: IntArray, slots: IntArray) {
    private val sourceColumns = columns.copyOf()
    private val sourceUnits = unitRows.copyOf()
    private val pivotRows = rows.copyOf()
    private val pivotSlots = slots.copyOf()

    val columns: IntArray get() = sourceColumns.copyOf()
    val unitRows: IntArray get() = sourceUnits.copyOf()
    val rows: IntArray get() = pivotRows.copyOf()
    val slots: IntArray get() = pivotSlots.copyOf()
}

// One mutable owner per fixed source matrix. Headings name source columns in original basis-slot order;
// a repaired slot may instead name a synthesized unit row. Accepted updates adopt the new basis even when
// advising a rebuild; SINGULAR preserves the old factors. Numerical repair rejection is not an exact rank claim.
internal interface BasisSolver : AutoCloseable {
    val n: Int
    val nnz: Int
    val updateCount: Int
    val singular: Boolean
    val rcond: Double
    val refactorizeReason: RefactorizeReason? get() = null
    val kernel: BasisKernel? get() = null
    val basisWork: BasisWork? get() = null
    val basisOperationWork: BasisOperationWork? get() = null

    fun refactorize(basicIndex: IntArray): Boolean
    fun ftran(x: IndexedVector, expectedDensity: Double = 1.0)
    fun btran(x: IndexedVector, expectedDensity: Double = 1.0)
    fun update(pivotRow: Int, entering: Int, spike: IndexedVector, pivotEta: IndexedVector? = null): BasisUpdate
    fun solveQuality(rhs: DoubleArray, solution: IndexedVector, transpose: Boolean = false): BasisSolveQuality

    fun refactorizeRepairing(basicIndex: IntArray, control: BasisRepairControl = BasisRepairControl()): BasisRepair? {
        val requested = basicIndex.copyOf()
        control.charge(requested.size.toLong())
        if (!control.check()) return null
        val factorized = control.measure(this) { refactorize(requested) }
        val result = if (factorized) BasisRepair(requested, IntArray(n) { -1 }) else null
        if (factorized) control.charge(3L * n)
        return result.takeIf { control.check() }
    }

    fun snapshot(): BasisSnapshot? = null
    fun ordering(): BasisOrdering? = null
    fun restore(snapshot: BasisSnapshot): Boolean = false
    fun extend(matrix: SparseMatrix, extension: BasisExtension): BasisExtensionResult? = null
    override fun close() {}
}
