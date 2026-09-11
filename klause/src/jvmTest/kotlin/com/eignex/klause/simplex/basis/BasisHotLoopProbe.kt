package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.koblas
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max

/** Explicit operation probe; excluded from ordinary test discovery. */
fun main(args: Array<String>) {
    println("kernels\t${koblas.vectorKernels.name}\t${koblas.sparseKernels.name}")
    for (id in listOf("mps-adlittle", "mps-afiro", "mzn-timetabling")) {
        val trace = BasisTraceCodec.read(Path.of(args[0], "$id.kbtrace"))
        HotLoopProbe(trace).use { it.run() }
    }
}

private class HotLoopProbe(private val trace: BasisTrace) : AutoCloseable {
    private val matrix = SparseMatrix.wrap(
        trace.matrix.rows, trace.matrix.columns, trace.matrix.copyColumnPointers(),
        trace.matrix.copyRowIndices(), DoubleArray(trace.matrix.entries) { trace.matrix.valueAt(it) },
    )
    private val solver = KotlinBasisSolver(matrix)
    private val headings = IntArray(matrix.rows)
    private val spike = IndexedVector(matrix.rows)
    private val row = IndexedVector(matrix.rows)
    private val input = IndexedVector(matrix.rows)
    private val bean = (ManagementFactory.getThreadMXBean() as ThreadMXBean).also {
        check(it.isThreadAllocatedMemorySupported)
        it.isThreadAllocatedMemoryEnabled = true
    }
    private val thread = Thread.currentThread().threadId()
    private var updates = 0

    fun run() {
        for (operation in trace.operations) {
            when (operation) {
                is BasisTraceOperation.Factorize -> {
                    operation.headings.forEachIndexed { i, heading -> headings[i] = headingColumn(heading, trace.sourceColumns) }
                    check(solver.refactorize(headings) == operation.success)
                }
                is BasisTraceOperation.Solve -> Unit
                is BasisTraceOperation.Update -> {
                    val entering = headingColumn(operation.entering, trace.sourceColumns)
                    prepare(operation.leavingSlot, entering)
                    if (operation.outcome != BasisUpdate.SINGULAR && updates++ == 2) {
                        probeUpdate(operation.leavingSlot, entering)
                    }
                    val result = solver.update(operation.leavingSlot, entering, spike, row)
                    check((result == BasisUpdate.SINGULAR) == (operation.outcome == BasisUpdate.SINGULAR))
                    if (result != BasisUpdate.SINGULAR) headings[operation.leavingSlot] = entering
                }
            }
        }
        println("shape\t${trace.metadata.id}\t${matrix.rows}\t${matrix.cols}\t${matrix.nnz}\t${solver.updateCount}")
        for (transpose in listOf(false, true)) {
            val rhs = trace.operations.filterIsInstance<BasisTraceOperation.Solve>().firstOrNull { it.transpose == transpose }?.rhs?.values()
                ?: trace.operations.filterIsInstance<BasisTraceOperation.Solve>().first().rhs.values()
            repeat(1000) { solve(rhs, transpose) }
            measure(if (transpose) "btran" else "ftran", 1000, { input.scatter(rhs) }) {
                if (transpose) solver.btran(input, 1.0) else solver.ftran(input, 1.0)
            }
            checkResidual(rhs, input, transpose)
        }
        repeat(1000) { check(!solver.basisOperationWork.saturated) }
        measure("operation-work-read", 1000) {
            val report = solver.basisOperationWork
            check(report.complete && !report.saturated && report.units > 0)
        }
        repeat(20) { check(solver.refactorize(headings)) }
        measure("rebuild", 20) { check(solver.refactorize(headings)) }
        val rhs = DoubleArray(matrix.rows) { if (it % 7 == 0) 1.0 else 0.0 }
        solve(rhs, false)
        checkResidual(rhs, input, false)
    }

    private fun probeUpdate(slot: Int, entering: Int) {
        val snapshot = checkNotNull(solver.snapshot())
        try {
            for (composed in listOf(false, true)) {
                repeat(100) {
                    check(solver.restore(snapshot))
                    prepare(slot, entering)
                    check(solver.update(slot, entering, spike, row) != BasisUpdate.SINGULAR)
                }
                measure(if (composed) "prepared-update" else "update", 100, {
                    check(solver.restore(snapshot))
                    if (!composed) prepare(slot, entering)
                }) {
                    if (composed) prepare(slot, entering)
                    check(solver.update(slot, entering, spike, row) != BasisUpdate.SINGULAR)
                }
                val old = headings[slot]
                headings[slot] = entering
                val rhs = DoubleArray(matrix.rows) { if (it % 5 == 0) 1.0 else 0.0 }
                solve(rhs, false)
                checkResidual(rhs, input, false)
                solve(rhs, true)
                checkResidual(rhs, input, true)
                headings[slot] = old
            }
            check(solver.restore(snapshot))
            prepare(slot, entering)
        } finally {
            snapshot.close()
        }
    }

    private fun prepare(slot: Int, entering: Int) {
        spike.scatterColumn(matrix, entering)
        solver.ftran(spike, 1.0)
        row.unit(slot)
        solver.btran(row, 1.0)
    }

    private fun solve(rhs: DoubleArray, transpose: Boolean) {
        input.scatter(rhs)
        if (transpose) solver.btran(input, 1.0) else solver.ftran(input, 1.0)
    }

    private fun checkResidual(rhs: DoubleArray, result: IndexedVector, transpose: Boolean) {
        val product = DoubleArray(matrix.rows)
        for (slot in headings.indices) {
            matrix.forEachInColumn(headings[slot]) { i, value ->
                if (transpose) product[slot] += value * result[i] else product[i] += value * result[slot]
            }
        }
        var residual = 0.0
        var scale = 1.0
        for (i in rhs.indices) {
            check(product[i].isFinite())
            residual = max(residual, abs(product[i] - rhs[i]))
            scale = max(scale, max(abs(product[i]), abs(rhs[i])))
        }
        check(residual <= 1e-10 + 1e-9 * scale) { "${trace.metadata.id}: residual $residual scale $scale" }
        println("residual\t${trace.metadata.id}\t$transpose\t$residual\t$scale")
    }

    private inline fun measure(name: String, count: Int, setup: () -> Unit = {}, action: () -> Unit) {
        var bytes = 0L
        var nanos = 0L
        var units = 0L
        repeat(count) {
            setup()
            val before = solver.basisOperationWork
            val allocated = bean.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            action()
            nanos += System.nanoTime() - start
            bytes += bean.getThreadAllocatedBytes(thread) - allocated
            val after = solver.basisOperationWork
            check(before.complete && after.complete)
            units += after.refactorization.units - before.refactorization.units +
                after.ftran.units - before.ftran.units + after.btran.units - before.btran.units +
                after.update.units - before.update.units
        }
        println("measure\t${trace.metadata.id}\t$name\t$count\t${bytes.toDouble() / count}\t${nanos.toDouble() / count}")
        println("work\t${trace.metadata.id}\t$name\t${units.toDouble() / count}")
    }

    override fun close() = solver.close()
}
