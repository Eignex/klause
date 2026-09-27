package com.eignex.klause.simplex.basis

import com.eignex.koblas.KoblasException
import com.eignex.koblas.SparseMatrix
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.max

internal data class RebuildPhase(var elapsedNanos: Long = 0, var cpuNanos: Long = 0, var javaBytes: Long = 0) {
    fun add(other: RebuildPhase) {
        elapsedNanos += other.elapsedNanos
        cpuNanos += other.cpuNanos
        javaBytes = if (javaBytes < 0 || other.javaBytes < 0) -1 else javaBytes + other.javaBytes
    }
}

internal data class RebuildArmReport(
    val builds: Int,
    val ftrans: Int,
    val btrans: Int,
    val updates: Int,
    val advisedUpdates: Int,
    val declines: Int,
    val maxUpdateChain: Int,
    val maxResidual: Double,
    val maxFreshDifference: Double,
    val errors: List<String>,
    val setup: RebuildPhase,
    val factor: RebuildPhase,
    val solve: RebuildPhase,
    val update: RebuildPhase,
    val finalization: RebuildPhase,
    val backendWork: BasisOperationWork?,
) {
    val totalElapsedNanos: Long get() = setup.elapsedNanos + factor.elapsedNanos + solve.elapsedNanos +
        update.elapsedNanos + finalization.elapsedNanos
    val totalCpuNanos: Long get() = setup.cpuNanos + factor.cpuNanos + solve.cpuNanos +
        update.cpuNanos + finalization.cpuNanos
    val totalJavaBytes: Long get() = listOf(setup, factor, solve, update, finalization).let { phases ->
        if (phases.any { it.javaBytes < 0 }) -1 else phases.sumOf { it.javaBytes }
    }
}

internal data class BasisRebuildComparisonReport(
    val deferredCheckpoint: Int,
    val nextCheckpoint: Int,
    val captured: RebuildArmReport,
    val deferred: RebuildArmReport,
    val pairedErrors: List<String>,
) {
    val valid: Boolean get() = captured.errors.isEmpty() && deferred.errors.isEmpty() && pairedErrors.isEmpty()
}

/** A bounded operation replay. It cannot establish source LP feasibility or exact certification. */
internal object BasisRebuildComparison {
    const val MAX_OPERATIONS = 256
    const val MAX_UPDATES_SINCE_BUILD = 16
    private const val ABS_TOLERANCE = 1e-10
    private const val REL_TOLERANCE = 1e-9

    fun compare(
        trace: BasisTrace,
        deferredCheckpoint: Int,
        factory: (SparseMatrix) -> BasisSolver = ::KotlinBasisSolver,
        cancelled: () -> Boolean = { false },
        deferredFirst: Boolean = false,
    ): BasisRebuildComparisonReport {
        BasisTraceCodec.validate(trace)
        require(trace.operations.size <= MAX_OPERATIONS)
        require(!trace.metadata.truncated && trace.metadata.termination == "completed") {
            "comparison requires a complete captured operation window"
        }
        val nextCheckpoint = eligibleNextCheckpoint(trace, deferredCheckpoint)
        val validation = runPair(trace, deferredCheckpoint, nextCheckpoint, factory, cancelled, deferredFirst, true)
        if (!validation.valid) return validation
        return runPair(trace, deferredCheckpoint, nextCheckpoint, factory, cancelled, deferredFirst, false)
    }

    private fun runPair(
        trace: BasisTrace,
        deferredCheckpoint: Int,
        nextCheckpoint: Int,
        factory: (SparseMatrix) -> BasisSolver,
        cancelled: () -> Boolean,
        deferredFirst: Boolean,
        validate: Boolean,
    ): BasisRebuildComparisonReport {
        val meter = RebuildMeter()
        val first = Arm(trace, factory, meter, validate, deferredCheckpoint.takeIf { deferredFirst })
        var second: Arm? = null
        try {
            second = Arm(trace, factory, meter, validate, deferredCheckpoint.takeUnless { deferredFirst })
            val captured = if (deferredFirst) second else first
            val deferred = if (deferredFirst) first else second
            val pairedErrors = ArrayList<String>()
            for ((index, operation) in trace.operations.withIndex()) {
                if (cancelled()) {
                    captured.fail(index, "cancelled")
                    deferred.fail(index, "cancelled")
                    break
                }
                applyOrRecord(captured, index, operation, skipBuild = false)
                applyOrRecord(deferred, index, operation, skipBuild = index == deferredCheckpoint)
                if (captured.active && deferred.active && captured.headings != deferred.headings) {
                    pairedErrors += "operation=$index ordered headings differ"
                }
                if (captured.errors.isNotEmpty() || deferred.errors.isNotEmpty() || pairedErrors.isNotEmpty()) break
            }
            if (!validate && captured.errors.isEmpty() && deferred.errors.isEmpty()) {
                captured.verifyRecordedSolves()
                deferred.verifyRecordedSolves()
            }
            return finish(captured, deferred, deferredCheckpoint, nextCheckpoint, pairedErrors, deferredFirst)
        } catch (failure: Throwable) {
            closeAfterFailure(failure, second, first)
            throw failure
        }
    }

    private fun applyOrRecord(arm: Arm, index: Int, operation: BasisTraceOperation, skipBuild: Boolean) {
        try {
            arm.apply(index, operation, skipBuild)
        } catch (failure: BasisArithmeticException) {
            arm.fail(index, "numerical failure: ${failure.message}")
        } catch (failure: KoblasException) {
            arm.fail(index, "numerical failure: ${failure.message}")
        }
    }

    private fun finish(
        captured: Arm,
        deferred: Arm,
        deferredCheckpoint: Int,
        nextCheckpoint: Int,
        pairedErrors: List<String>,
        deferredFirst: Boolean,
    ): BasisRebuildComparisonReport {
        val owners = if (deferredFirst) listOf(deferred, captured) else listOf(captured, deferred)
        for (owner in owners) {
            try {
                owner.close()
            } catch (error: Throwable) {
                owner.fail(-1, "finalization exception: ${error.message}")
            }
        }
        return BasisRebuildComparisonReport(
            deferredCheckpoint,
            nextCheckpoint,
            captured.report(),
            deferred.report(),
            pairedErrors,
        )
    }

    private fun closeAfterFailure(failure: Throwable, vararg owners: Arm?) {
        for (owner in owners) {
            val priorErrors = owner?.errors?.size ?: 0
            try {
                owner?.close()
            } catch (closeFailure: Throwable) {
                if (failure !== closeFailure) failure.addSuppressed(closeFailure)
            }
            owner?.errors?.drop(priorErrors)?.forEach { failure.addSuppressed(IllegalStateException(it)) }
        }
    }

    private fun eligibleNextCheckpoint(trace: BasisTrace, deferredCheckpoint: Int): Int {
        require(deferredCheckpoint in trace.operations.indices)
        var headings: List<BasisHeading>? = null
        var eligible = false
        var next = -1
        for ((index, operation) in trace.operations.withIndex()) {
            when (operation) {
                is BasisTraceOperation.Factorize -> {
                    if (index == deferredCheckpoint) {
                        require(operation.success && headings == operation.headings) {
                            "deferred checkpoint must be a successful same-heading rebuild"
                        }
                        eligible = true
                    } else if (eligible && next < 0) {
                        next = index
                    }
                    headings = operation.headings.takeIf { operation.success }
                }

                is BasisTraceOperation.Update -> if (operation.outcome != BasisUpdate.SINGULAR) {
                    headings = requireNotNull(
                        headings,
                    ).toMutableList().also { it[operation.leavingSlot] = operation.entering }
                }

                is BasisTraceOperation.Solve -> Unit
            }
        }
        require(eligible && next > deferredCheckpoint && next < trace.operations.lastIndex) {
            "window needs a later captured build and following operations"
        }
        require((trace.operations[next] as BasisTraceOperation.Factorize).success) {
            "next checkpoint must build successfully"
        }
        val followingCheckpoint = (next + 1 until trace.operations.size).firstOrNull {
            trace.operations[it] is BasisTraceOperation.Factorize
        } ?: trace.operations.size
        require(trace.operations.subList(next + 1, followingCheckpoint).any { it is BasisTraceOperation.Solve }) {
            "window needs a solve on the next build"
        }
        return next
    }

    private class Arm(
        private val trace: BasisTrace,
        factory: (SparseMatrix) -> BasisSolver,
        private val meter: RebuildMeter,
        private val validate: Boolean,
        private val deferredCheckpoint: Int?,
    ) : AutoCloseable {
        private val solver: BasisSolver
        private val fresh: BasisSolver
        private val carriers = HashMap<Int, IndexedVector>()
        private val prepared = HashMap<Int, IndexedVector>()
        private val recordedSolves = ArrayList<RecordedSolve>()
        var headings: List<BasisHeading>? = null
            private set
        var active = false
            private set
        val errors = ArrayList<String>()
        private var builds = 0
        private var ftrans = 0
        private var btrans = 0
        private var updates = 0
        private var advised = 0
        private var declines = 0
        private var maxChain = 0
        private var maxResidual = 0.0
        private var maxFreshDifference = 0.0
        private val setup = RebuildPhase()
        private val factor = RebuildPhase()
        private val solve = RebuildPhase()
        private val update = RebuildPhase()
        private val finalization = RebuildPhase()
        private var backendWork: BasisOperationWork? = null
        private var closed = false

        init {
            val created = meter.measure(setup) { factory(trace.matrix.toSparseMatrix()) }
            solver = created
            try {
                fresh = KotlinBasisSolver(trace.matrix.toSparseMatrix())
            } catch (failure: Throwable) {
                try {
                    solver.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        fun apply(index: Int, operation: BasisTraceOperation, skipBuild: Boolean) {
            if (operation is BasisTraceOperation.Factorize) {
                prepared.clear()
                if (skipBuild) {
                    require(active && headings == operation.headings)
                    return
                }
                val success = meter.measure(factor) {
                    val columns = operation.headings.map { headingColumn(it, trace.sourceColumns) }.toIntArray()
                    solver.refactorize(columns)
                }
                builds++
                if (success != operation.success) fail(index, "build outcome $success differs from capture")
                active = success
                headings = operation.headings.takeIf { success }
                if (success && validate) checkBasis(index)
                return
            }
            if (!active) return
            when (operation) {
                is BasisTraceOperation.Solve -> {
                    val (vector, rhs) = meter.measure(solve) {
                        val rhs = operation.rhs.values()
                        val density = Double.fromBits(operation.expectedDensityBits)
                        val target = carriers.getOrPut(operation.carrier) { IndexedVector(trace.matrix.rows) }
                        target.scatter(rhs)
                        if (operation.transpose) solver.btran(target, density) else solver.ftran(target, density)
                        prepared[index] = target
                        target to rhs
                    }
                    if (operation.transpose) btrans++ else ftrans++
                    val result = vector.toDoubleArray()
                    if (validate) {
                        checkSolve(index, rhs, result, operation.transpose)
                    } else {
                        recordedSolves += RecordedSolve(
                            index,
                            rhs,
                            result,
                            operation.transpose,
                            requireNotNull(headings),
                        )
                    }
                }

                is BasisTraceOperation.Update -> {
                    if (solver.updateCount >= MAX_UPDATES_SINCE_BUILD) {
                        fail(index, "harness update limit $MAX_UPDATES_SINCE_BUILD")
                        return
                    }
                    val spike = prepared[operation.spikeOperation] ?: return fail(index, "missing spike")
                    val pivot = operation.pivotOperation?.let { prepared[it] ?: return fail(index, "missing pivot") }
                    val outcome = meter.measure(update) {
                        solver.update(
                            operation.leavingSlot,
                            headingColumn(operation.entering, trace.sourceColumns),
                            spike,
                            pivot,
                        )
                    }
                    prepared.clear()
                    if (deferredCheckpoint == null &&
                        (outcome == BasisUpdate.SINGULAR) != (operation.outcome == BasisUpdate.SINGULAR)
                    ) {
                        fail(index, "captured update acceptance differs")
                    }
                    if (outcome == BasisUpdate.SINGULAR) {
                        declines++
                        active = false
                        headings = null
                        fail(index, "update declined under changed schedule")
                        return
                    }
                    updates++
                    if (outcome == BasisUpdate.REFACTORIZE) advised++
                    headings = requireNotNull(
                        headings,
                    ).toMutableList().also { it[operation.leavingSlot] = operation.entering }
                    maxChain = max(maxChain, solver.updateCount)
                    if (validate) checkBasis(index)
                    if (outcome == BasisUpdate.REFACTORIZE &&
                        (
                            trace.operations.getOrNull(index + 1) !is BasisTraceOperation.Factorize ||
                                index + 1 == deferredCheckpoint
                            )
                    ) {
                        fail(index, "backend advised a mandatory rebuild before the next checkpoint")
                    }
                }

                is BasisTraceOperation.Factorize -> Unit
            }
        }

        private fun checkBasis(index: Int) {
            val current = requireNotNull(headings)
            val columns = current.map { headingColumn(it, trace.sourceColumns) }.toIntArray()
            if (!fresh.refactorize(columns)) {
                fail(index, "fresh basis declined")
                return
            }
            val rhs = DoubleArray(trace.matrix.rows) { row -> if (row == index % trace.matrix.rows) 1.0 else 0.0 }
            for (transpose in listOf(false, true)) {
                val value = IndexedVector(rhs.size).also { it.scatter(rhs) }
                val control = IndexedVector(rhs.size).also { it.scatter(rhs) }
                if (transpose) {
                    solver.btran(value, 0.0)
                    fresh.btran(control, 0.0)
                } else {
                    solver.ftran(value, 0.0)
                    fresh.ftran(control, 0.0)
                }
                checkValues(index, rhs, value.toDoubleArray(), control.toDoubleArray(), transpose)
            }
        }

        private fun checkSolve(index: Int, rhs: DoubleArray, value: DoubleArray, transpose: Boolean) {
            val columns = requireNotNull(headings).map { headingColumn(it, trace.sourceColumns) }.toIntArray()
            if (!fresh.refactorize(columns)) return fail(index, "fresh basis declined")
            val control = IndexedVector(rhs.size).also { it.scatter(rhs) }
            if (transpose) fresh.btran(control, 0.0) else fresh.ftran(control, 0.0)
            checkValues(index, rhs, value, control.toDoubleArray(), transpose)
        }

        fun verifyRecordedSolves() {
            for (record in recordedSolves) {
                val columns = record.headings.map { headingColumn(it, trace.sourceColumns) }.toIntArray()
                if (!fresh.refactorize(columns)) {
                    fail(record.index, "fresh basis declined")
                    continue
                }
                val control = IndexedVector(record.rhs.size).also { it.scatter(record.rhs) }
                if (record.transpose) fresh.btran(control, 0.0) else fresh.ftran(control, 0.0)
                checkValues(
                    record.index,
                    record.rhs,
                    record.value,
                    control.toDoubleArray(),
                    record.transpose,
                    record.headings,
                )
            }
        }

        private fun checkValues(
            index: Int,
            rhs: DoubleArray,
            value: DoubleArray,
            control: DoubleArray,
            transpose: Boolean,
            current: List<BasisHeading> = requireNotNull(headings),
        ) {
            val residual = sourceResidual(trace.matrix, current, trace.sourceColumns, rhs, value, transpose)
            if (!residual.absolute.isFinite() || !residual.scale.isFinite() || value.any { !it.isFinite() }) {
                return fail(index, "nonfinite source state")
            }
            maxResidual = max(maxResidual, residual.relative)
            if (residual.absolute > ABS_TOLERANCE + REL_TOLERANCE * residual.scale) {
                fail(index, "source residual ${residual.absolute} exceeds tolerance")
            }
            for (row in value.indices) {
                val difference = abs(value[row] - control[row])
                maxFreshDifference = max(maxFreshDifference, difference)
                if (!difference.isFinite() || difference > ABS_TOLERANCE + REL_TOLERANCE * max(
                        abs(value[row]),
                        abs(control[row]),
                    )
                ) {
                    fail(index, "fresh state differs at row $row")
                    break
                }
            }
        }

        fun fail(index: Int, message: String) {
            errors += "operation=$index $message"
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                backendWork = solver.basisOperationWork
            } catch (error: Throwable) {
                errors += "finalization work failure: ${error.message}"
            }
            try {
                meter.measure(finalization) { solver.close() }
            } catch (error: Throwable) {
                errors += "finalization solver close failure: ${error.message}"
            }
            try {
                fresh.close()
            } catch (error: Throwable) {
                errors += "finalization oracle close failure: ${error.message}"
            }
        }

        fun report() = RebuildArmReport(
            builds, ftrans, btrans, updates, advised, declines, maxChain, maxResidual,
            maxFreshDifference, errors.toList(), setup, factor, solve, update, finalization, backendWork,
        )
    }

    private data class RecordedSolve(
        val index: Int,
        val rhs: DoubleArray,
        val value: DoubleArray,
        val transpose: Boolean,
        val headings: List<BasisHeading>,
    )

    private class RebuildMeter {
        private val thread = Thread.currentThread().threadId()
        private val cpu = ManagementFactory.getThreadMXBean()
        private val allocation = cpu as? ThreadMXBean

        init {
            if (cpu.isThreadCpuTimeSupported) cpu.isThreadCpuTimeEnabled = true
            if (allocation?.isThreadAllocatedMemorySupported == true) allocation.isThreadAllocatedMemoryEnabled = true
        }

        fun <T> measure(phase: RebuildPhase, block: () -> T): T {
            val beforeCpu = if (cpu.isThreadCpuTimeEnabled) cpu.getThreadCpuTime(thread) else -1L
            val beforeBytes = if (allocation?.isThreadAllocatedMemoryEnabled == true) {
                allocation.getThreadAllocatedBytes(thread)
            } else {
                -1L
            }
            val beforeWall = System.nanoTime()
            try {
                return block()
            } finally {
                phase.elapsedNanos += System.nanoTime() - beforeWall
                val afterCpu = if (beforeCpu >= 0) cpu.getThreadCpuTime(thread) else -1L
                phase.cpuNanos = if (afterCpu >= 0) phase.cpuNanos + afterCpu - beforeCpu else -1L
                val afterBytes = if (beforeBytes >= 0) allocation!!.getThreadAllocatedBytes(thread) else -1L
                phase.javaBytes = if (afterBytes >= 0 && phase.javaBytes >= 0) {
                    phase.javaBytes + afterBytes - beforeBytes
                } else {
                    -1L
                }
            }
        }
    }
}
