package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.max

internal data class BasisReplayTiming(
    var setupNanos: Long = 0,
    var buildNanos: Long = 0,
    var ftranNanos: Long = 0,
    var btranNanos: Long = 0,
    var updateOnlyNanos: Long = 0,
    var lifecycleNanos: Long = 0,
    var setupBytes: Long = 0,
    var buildBytes: Long = 0,
    var ftranBytes: Long = 0,
    var btranBytes: Long = 0,
    var updateOnlyBytes: Long = 0,
    var preparedUpdateNanos: Long = 0,
    var preparedUpdateBytes: Long = 0,
)

internal data class BasisReplayReport(
    val backend: String,
    val adapterInclusive: Boolean,
    val allocationCoverage: String,
    val builds: Int,
    val ftrans: Int,
    val btrans: Int,
    val acceptedUpdates: Int,
    val advisedUpdates: Int,
    val declinedUpdates: Int,
    val checkpoints: Int,
    val peakFill: Int,
    val maxChainAge: Int,
    val absoluteResidual: Double,
    val relativeResidual: Double,
    val residualTolerance: Double,
    val stateErrors: Int,
    val errors: List<String>,
    val timing: BasisReplayTiming,
)

internal data class BasisReplayPair(val custom: BasisReplayReport, val hfactor: BasisReplayReport)

internal object BasisTraceReplay {
    fun replay(
        trace: BasisTrace,
        customFactory: (SparseMatrix) -> BasisSolver = ::KotlinBasisSolver,
        referenceFactory: (SparseMatrix) -> BasisSolver = ::HfactorBasisSolver,
    ): BasisReplayPair {
        BasisTraceCodec.validate(trace)
        val matrix = trace.matrix.toSparseMatrix()
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        if (bean.isThreadAllocatedMemorySupported) bean.isThreadAllocatedMemoryEnabled = true
        val custom = newArm("custom", false, matrix, trace, bean, customFactory)
        val hfactor = newArm("hfactor", true, matrix, trace, bean, referenceFactory)
        try {
            for ((index, operation) in trace.operations.withIndex()) {
                val customErrors = custom.errorCount
                val hfactorErrors = hfactor.errorCount
                custom.apply(index, operation)
                hfactor.apply(index, operation)
                val customFailed = custom.errorCount != customErrors
                val hfactorFailed = hfactor.errorCount != hfactorErrors
                if (customFailed || hfactorFailed) {
                    if (!customFailed) custom.stopForPeer(index)
                    if (!hfactorFailed) hfactor.stopForPeer(index)
                } else if (operation is BasisTraceOperation.Update && custom.lastAccepted != hfactor.lastAccepted) {
                    custom.diverge(index)
                    hfactor.diverge(index)
                }
            }
        } finally {
            custom.close()
            hfactor.close()
        }
        return BasisReplayPair(custom.report(), hfactor.report())
    }

    private fun newArm(
        name: String,
        adapter: Boolean,
        matrix: SparseMatrix,
        trace: BasisTrace,
        bean: ThreadMXBean,
        factory: (SparseMatrix) -> BasisSolver,
    ): Arm {
        val thread = Thread.currentThread().threadId()
        val beforeBytes = if (bean.isThreadAllocatedMemoryEnabled) bean.getThreadAllocatedBytes(thread) else -1L
        val beforeNanos = System.nanoTime()
        val solver = factory(matrix)
        val nanos = System.nanoTime() - beforeNanos
        val bytes = if (beforeBytes >= 0L) bean.getThreadAllocatedBytes(thread) - beforeBytes else -1L
        val preparedSolver = factory(matrix)
        return Arm(name, adapter, matrix, solver, preparedSolver, trace, bean, nanos, bytes)
    }

    private class Arm(
        private val name: String,
        private val adapter: Boolean,
        private val matrix: SparseMatrix,
        private val solver: BasisSolver,
        private val preparedSolver: BasisSolver,
        private val trace: BasisTrace,
        private val bean: ThreadMXBean,
        setupNanos: Long,
        setupBytes: Long,
    ) {
        private val vectors = HashMap<Int, IndexedVector>()
        private val solved = HashMap<Int, DoubleArray>()
        private var headings: MutableList<BasisHeading>? = null
        private var active = false
        private var builds = 0
        private var ftrans = 0
        private var btrans = 0
        private var accepted = 0
        private var advised = 0
        private var declined = 0
        private var checkpoints = 0
        private var peakFill = 0
        private var chainAge = 0
        private var maxChainAge = 0
        private var absoluteResidual = 0.0
        private var relativeResidual = 0.0
        private var residualTolerance = 0.0
        private var stateErrors = 0
        private val errors = ArrayList<String>()
        private val timing = BasisReplayTiming(setupNanos = setupNanos, setupBytes = setupBytes)
        var lastAccepted: Boolean? = null
            private set
        val errorCount: Int get() = stateErrors

        fun apply(index: Int, operation: BasisTraceOperation) {
            when (operation) {
                is BasisTraceOperation.Factorize -> factorize(index, operation)
                is BasisTraceOperation.Solve -> if (active) solve(index, operation)
                is BasisTraceOperation.Update -> if (active) update(index, operation)
            }
        }

        private fun factorize(index: Int, operation: BasisTraceOperation.Factorize) {
            checkpoints++
            solved.clear()
            lastAccepted = null
            val raw = operation.headings.map { headingColumn(it, trace.sourceColumns) }.toIntArray()
            val measured = measure { solver.refactorize(raw) }
            val preparedResult = preparedSolver.refactorize(raw)
            timing.buildNanos += measured.nanos
            timing.buildBytes += measured.bytes
            timing.lifecycleNanos += measured.nanos
            builds++
            if (measured.value != operation.success) {
                fail(index, "factorization outcome ${measured.value} != captured ${operation.success}")
                headings = null
                active = false
                return
            }
            if (preparedResult != operation.success) {
                fail(index, "synthetic prepared factorization outcome $preparedResult != captured ${operation.success}")
                return
            }
            active = measured.value
            headings = operation.headings.toMutableList().takeIf { active }
            chainAge = 0
            peakFill = max(peakFill, solver.nnz)
            if (active) checkBasisResidual(index)
        }

        private fun solve(index: Int, operation: BasisTraceOperation.Solve) {
            val vector = vectors.getOrPut(operation.carrier) { IndexedVector(trace.matrix.rows) }
            val rhs = operation.rhs.values()
            vector.scatter(rhs)
            val measured = measure {
                if (operation.transpose) {
                    solver.btran(vector, Double.fromBits(operation.expectedDensityBits))
                } else {
                    solver.ftran(vector, Double.fromBits(operation.expectedDensityBits))
                }
            }
            if (operation.transpose) {
                btrans++
                timing.btranNanos += measured.nanos
                timing.btranBytes += measured.bytes
            } else {
                ftrans++
                timing.ftranNanos += measured.nanos
                timing.ftranBytes += measured.bytes
            }
            val values = vector.toDoubleArray()
            solved[index] = values
            timing.lifecycleNanos += measured.nanos
            checkResidual(index, rhs, values, operation.transpose)
        }

        private fun update(index: Int, operation: BasisTraceOperation.Update) {
            val spikeSolve = trace.operations[operation.spikeOperation] as BasisTraceOperation.Solve
            val spike = vectors[spikeSolve.carrier] ?: return fail(index, "missing prepared spike carrier")
            val capturedSpike = solved[operation.spikeOperation] ?: return fail(index, "missing prepared spike solve")
            if (!closeVectors(capturedSpike, operation.spikeEvidence.values())) {
                return fail(index, "prepared spike does not match captured evidence")
            }
            val pivot = operation.pivotOperation?.let { pivotIndex ->
                val pivotSolve = trace.operations[pivotIndex] as BasisTraceOperation.Solve
                val vector = vectors[pivotSolve.carrier] ?: return fail(index, "missing prepared pivot carrier")
                val capturedPivot = solved[pivotIndex] ?: return fail(index, "missing prepared pivot solve")
                val evidence = requireNotNull(operation.pivotEvidence)
                if (!closeVectors(capturedPivot, evidence.values())) {
                    return fail(index, "prepared pivot does not match captured evidence")
                }
                vector
            }
            val entering = headingColumn(operation.entering, trace.sourceColumns)
            val measured = measure { solver.update(operation.leavingSlot, entering, spike, pivot) }
            timing.updateOnlyNanos += measured.nanos
            timing.updateOnlyBytes += measured.bytes
            timing.lifecycleNanos += measured.nanos
            val prepared = prepareUpdate(operation, entering)
            timing.preparedUpdateNanos += prepared.nanos
            timing.preparedUpdateBytes += prepared.bytes
            val capturedAccepted = operation.outcome != BasisUpdate.SINGULAR
            val armAccepted = measured.value != BasisUpdate.SINGULAR
            val preparedAccepted = prepared.value != BasisUpdate.SINGULAR
            lastAccepted = armAccepted
            if (capturedAccepted != armAccepted) {
                declined++
                return fail(index, "update acceptance ${measured.value} != captured ${operation.outcome}")
            }
            if (capturedAccepted != preparedAccepted) {
                declined++
                return fail(index, "synthetic prepared acceptance ${prepared.value} != captured ${operation.outcome}")
            }
            if (!armAccepted) {
                declined++
                active = false
                headings = null
                return
            }
            accepted++
            if (measured.value == BasisUpdate.REFACTORIZE) advised++
            headings?.set(operation.leavingSlot, operation.entering)
            chainAge++
            maxChainAge = max(maxChainAge, chainAge)
            peakFill = max(peakFill, solver.nnz)
            checkBasisResidual(index)
            checkBasisResidual(index, preparedSolver, "synthetic prepared")
        }

        private fun prepareUpdate(operation: BasisTraceOperation.Update, entering: Int): Measurement<BasisUpdate> {
            val spikeSolve = trace.operations[operation.spikeOperation] as BasisTraceOperation.Solve
            val spike = IndexedVector(trace.matrix.rows).also { it.scatter(spikeSolve.rhs.values()) }
            val pivot = operation.pivotOperation?.let { pivotIndex ->
                val pivotSolve = trace.operations[pivotIndex] as BasisTraceOperation.Solve
                IndexedVector(trace.matrix.rows).also { it.scatter(pivotSolve.rhs.values()) }
            }
            return measure {
                preparedSolver.ftran(spike, Double.fromBits(spikeSolve.expectedDensityBits))
                operation.pivotOperation?.let { pivotIndex ->
                    val pivotSolve = trace.operations[pivotIndex] as BasisTraceOperation.Solve
                    preparedSolver.btran(requireNotNull(pivot), Double.fromBits(pivotSolve.expectedDensityBits))
                }
                preparedSolver.update(operation.leavingSlot, entering, spike, pivot)
            }
        }

        fun diverge(index: Int) {
            fail(index, "cross-backend update divergence")
            active = false
            headings = null
        }

        fun stopForPeer(index: Int) = fail(index, "peer backend failed")

        private fun checkBasisResidual(
            operation: Int,
            basisSolver: BasisSolver = solver,
            context: String = "observed",
        ) {
            val basis = headings ?: return
            val rhs = DoubleArray(trace.matrix.rows) { row ->
                when {
                    row == operation.mod(trace.matrix.rows) -> 1.0
                    row % 7 == operation.mod(7) -> 0.125
                    else -> 0.0
                }
            }
            for (transpose in listOf(false, true)) {
                val vector = IndexedVector(trace.matrix.rows).also { it.scatter(rhs) }
                if (transpose) basisSolver.btran(vector, 0.0) else basisSolver.ftran(vector, 0.0)
                checkResidual(operation, rhs, vector.toDoubleArray(), transpose, basis, context)
            }
        }

        private fun checkResidual(
            operation: Int,
            rhs: DoubleArray,
            solution: DoubleArray,
            transpose: Boolean,
            basis: List<BasisHeading> = requireNotNull(headings),
            context: String = "observed",
        ) {
            if (rhs.any { !it.isFinite() } || solution.any { !it.isFinite() }) {
                fail(operation, "$context source residual has nonfinite input")
                return
            }
            val residual = sourceResidual(trace.matrix, basis, trace.sourceColumns, rhs, solution, transpose)
            if (!residual.absolute.isFinite() || !residual.relative.isFinite() || !residual.scale.isFinite()) {
                fail(operation, "$context source residual is nonfinite")
                return
            }
            absoluteResidual = max(absoluteResidual, residual.absolute)
            relativeResidual = max(relativeResidual, residual.relative)
            val tolerance = RESIDUAL_ABSOLUTE + RESIDUAL_RELATIVE * residual.scale
            residualTolerance = max(residualTolerance, tolerance)
            if (residual.absolute > tolerance) {
                fail(operation, "$context source residual ${residual.absolute} exceeds tolerance")
            }
        }

        private fun fail(operation: Int, message: String) {
            stateErrors++
            errors += "operation=$operation $message"
            active = false
            headings = null
            lastAccepted = null
        }

        fun close() {
            solver.close()
            preparedSolver.close()
        }

        fun report() = BasisReplayReport(
            name,
            adapter,
            if (adapter) "java-thread-only;native-heap-excluded" else "java-thread",
            builds,
            ftrans,
            btrans,
            accepted,
            advised,
            declined,
            checkpoints,
            peakFill,
            maxChainAge,
            absoluteResidual,
            relativeResidual,
            residualTolerance,
            stateErrors,
            errors.toList(),
            timing,
        )

        private fun <T> measure(block: () -> T): Measurement<T> {
            val thread = Thread.currentThread().threadId()
            val beforeBytes = if (bean.isThreadAllocatedMemoryEnabled) bean.getThreadAllocatedBytes(thread) else -1L
            val beforeNanos = System.nanoTime()
            val value = block()
            val nanos = System.nanoTime() - beforeNanos
            val bytes = if (beforeBytes >= 0L) bean.getThreadAllocatedBytes(thread) - beforeBytes else -1L
            return Measurement(value, nanos, bytes)
        }
    }
}

private data class Measurement<T>(val value: T, val nanos: Long, val bytes: Long)
private data class SourceResidual(val absolute: Double, val relative: Double, val scale: Double)

private const val RESIDUAL_ABSOLUTE = 1e-10
private const val RESIDUAL_RELATIVE = 1e-9

private fun BasisTraceMatrix.toSparseMatrix(): SparseMatrix = SparseMatrix.wrap(
    rows,
    columns,
    copyColumnPointers(),
    copyRowIndices(),
    DoubleArray(entries) { valueAt(it) },
)

private fun sourceResidual(
    matrix: BasisTraceMatrix,
    headings: List<BasisHeading>,
    sourceColumns: Int,
    rhs: DoubleArray,
    solution: DoubleArray,
    transpose: Boolean,
): SourceResidual {
    val product = DoubleArray(matrix.rows)
    var normB = 0.0
    if (transpose) {
        for (slot in headings.indices) {
            val column = headingColumn(headings[slot], sourceColumns)
            var value = 0.0
            var columnNorm = 0.0
            for (entry in matrix.columnStart(column) until matrix.columnEnd(column)) {
                val coefficient = matrix.valueAt(entry)
                value += coefficient * solution[matrix.rowAt(entry)]
                columnNorm += abs(coefficient)
            }
            product[slot] = value
            normB = max(normB, columnNorm)
        }
    } else {
        val rowNorm = DoubleArray(matrix.rows)
        for (slot in headings.indices) {
            val column = headingColumn(headings[slot], sourceColumns)
            for (entry in matrix.columnStart(column) until matrix.columnEnd(column)) {
                val row = matrix.rowAt(entry)
                val coefficient = matrix.valueAt(entry)
                product[row] += coefficient * solution[slot]
                rowNorm[row] += abs(coefficient)
            }
        }
        normB = rowNorm.maxOrNull() ?: 0.0
    }
    var absolute = 0.0
    var normRhs = 0.0
    var normSolution = 0.0
    for (i in product.indices) {
        absolute = max(absolute, abs(product[i] - rhs[i]))
        normRhs = max(normRhs, abs(rhs[i]))
        normSolution = max(normSolution, abs(solution[i]))
    }
    val scale = max(1.0, normB * normSolution + normRhs)
    return SourceResidual(absolute, absolute / scale, scale)
}

private fun closeVectors(actual: DoubleArray, expected: DoubleArray): Boolean {
    if (actual.size != expected.size) return false
    for (i in actual.indices) {
        if (!actual[i].isFinite() || !expected[i].isFinite()) return false
        val scale = max(1.0, max(abs(actual[i]), abs(expected[i])))
        if (abs(actual[i] - expected[i]) > 1e-10 + 1e-9 * scale) return false
    }
    return true
}
