package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.max

private var meterSink = BasisWork()

// Explicit basis-only benchmark entry point; ordinary test discovery never runs measurements.
fun main(args: Array<String>) {
    val countsOnly = args.contentEquals(arrayOf("--counts-only"))
    require(args.isEmpty() || countsOnly)
    val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
    check(bean.isThreadAllocatedMemorySupported)
    bean.isThreadAllocatedMemoryEnabled = true
    if (!countsOnly) {
        for (shape in SHAPES) {
            for (reuse in listOf(false, true)) repeat(WARMUPS) { measureTrace(shape, reuse, bean) }
        }
        repeat(WARMUPS) { measureMeter(bean) }
    }
    repeat(REPETITIONS) { repetition ->
        for (shape in SHAPES) {
            for (reuse in listOf(false, true)) {
                val report = measureTrace(shape, reuse, bean)
                println("B4bbench repetition=$repetition $report")
            }
        }
        println("B4bbench repetition=$repetition ${measureFallback(bean)}")
        if (!countsOnly) println("B4bbench repetition=$repetition ${measureMeter(bean)}")
    }
}

private fun measureTrace(shape: String, reuse: Boolean, bean: ThreadMXBean): String {
    val source = ftSource(shape, DIMENSION)
    val solver = KotlinBasisSolver(
        source,
        updateLimit = 100,
        fillFactor = 100.0,
        reusePivotOrder = reuse,
    )
    val basis = IntArray(DIMENSION) { DIMENSION - 1 - it }
    check(solver.refactorize(basis))
    val thread = Thread.currentThread().threadId()
    var buildNanos = 0L
    var buildBytes = 0L
    var buildUnits = 0L
    var orderingAttempts = 0L
    var reusedOrders = 0L
    var fallbacks = 0L
    var ftranNanos = 0L
    var ftranBytes = 0L
    var btranNanos = 0L
    var btranBytes = 0L
    var updateNanos = 0L
    var updateBytes = 0L
    var residual = 0.0
    var peakNnz = solver.nnz
    repeat(HEADING_CHANGES) { step ->
        val slot = (step * 5 + 2) % DIMENSION
        basis[slot] = (basis[slot] + DIMENSION) % source.cols
        val beforeBytes = bean.getThreadAllocatedBytes(thread)
        val beforeNanos = System.nanoTime()
        check(solver.refactorize(basis))
        buildNanos += System.nanoTime() - beforeNanos
        buildBytes += bean.getThreadAllocatedBytes(thread) - beforeBytes
        val build = checkNotNull(solver.basisWork.build)
        buildUnits += build.units
        orderingAttempts += build.orderingAttempts
        reusedOrders += build.reusedOrders
        fallbacks += build.fallbacks
        peakNnz = max(peakNnz, solver.nnz)
        residual = max(residual, checkBothDirections(solver, source, basis, step))
    }
    repeat(SOLVES) { step ->
        val rhs = DoubleArray(DIMENSION) { i -> if (i == (step * 3 + 1) % DIMENSION) 1.0 else 0.0 }
        val vector = IndexedVector(DIMENSION).also { it.scatter(rhs) }
        var beforeBytes = bean.getThreadAllocatedBytes(thread)
        var beforeNanos = System.nanoTime()
        solver.ftran(vector, 0.0)
        ftranNanos += System.nanoTime() - beforeNanos
        ftranBytes += bean.getThreadAllocatedBytes(thread) - beforeBytes
        residual = max(residual, ftResidual(source, basis, rhs, vector, false))
        vector.scatter(rhs)
        beforeBytes = bean.getThreadAllocatedBytes(thread)
        beforeNanos = System.nanoTime()
        solver.btran(vector, 0.0)
        btranNanos += System.nanoTime() - beforeNanos
        btranBytes += bean.getThreadAllocatedBytes(thread) - beforeBytes
        residual = max(residual, ftResidual(source, basis, rhs, vector, true))
    }
    var accepted = 0
    var declined = 0
    repeat(UPDATES) { step ->
        val slot = (step * 7 + 3) % DIMENSION
        val entering = (basis[slot] + DIMENSION) % source.cols
        val spike = IndexedVector(DIMENSION).also { it.scatterColumn(source, entering) }
        solver.ftran(spike, 0.0)
        val beforeBytes = bean.getThreadAllocatedBytes(thread)
        val beforeNanos = System.nanoTime()
        val result = solver.update(slot, entering, spike)
        updateNanos += System.nanoTime() - beforeNanos
        updateBytes += bean.getThreadAllocatedBytes(thread) - beforeBytes
        if (result == BasisUpdate.SINGULAR) {
            declined++
        } else {
            accepted++
            basis[slot] = entering
            residual = max(residual, checkBothDirections(solver, source, basis, step + HEADING_CHANGES))
            peakNnz = max(peakNnz, solver.nnz)
        }
    }
    val work = solver.basisWork
    solver.close()
    val tolerance = if (shape == "near") 1e-8 else 1e-11
    check(residual <= tolerance) { "$shape reuse=$reuse residual=$residual" }
    return "shape=$shape arm=${if (reuse) "reuse" else "fresh"} residual=$residual " +
        "builds=$HEADING_CHANGES orderingAttempts=$orderingAttempts reusedOrders=$reusedOrders " +
        "fallbacks=$fallbacks buildUnits=$buildUnits buildNanos=$buildNanos buildBytes=$buildBytes " +
        "ftranCount=${work.ftran.successes} ftranUnits=${work.ftran.units} ftranNanos=$ftranNanos " +
        "ftranBytes=$ftranBytes btranCount=${work.btran.successes} btranUnits=${work.btran.units} " +
        "btranNanos=$btranNanos btranBytes=$btranBytes accepted=$accepted declined=$declined " +
        "updateUnits=${work.update.units} updateNanos=$updateNanos updateBytes=$updateBytes peakNnz=$peakNnz"
}

private fun measureFallback(bean: ThreadMXBean): String {
    val source = SparseMatrix.ofColumns(
        2,
        4,
        listOf(
            listOf(0 to 1.0),
            listOf(1 to 1.0),
            listOf(1 to 1.0),
            listOf(0 to 1.0, 1 to 1.0),
        ),
    )
    val solver = KotlinBasisSolver(source)
    check(solver.refactorize(intArrayOf(0, 1)))
    val thread = Thread.currentThread().threadId()
    val beforeBytes = bean.getThreadAllocatedBytes(thread)
    val beforeNanos = System.nanoTime()
    check(solver.refactorize(intArrayOf(2, 3)))
    val nanos = System.nanoTime() - beforeNanos
    val bytes = bean.getThreadAllocatedBytes(thread) - beforeBytes
    val build = checkNotNull(solver.basisWork.build)
    val vector = IndexedVector(2).also { it.unit(0) }
    solver.ftran(vector, 0.0)
    val residual = ftResidual(source, intArrayOf(2, 3), doubleArrayOf(1.0, 0.0), vector, false)
    solver.close()
    check(build.fallbacks == 1L && build.reusedOrders == 0L)
    check(residual <= 1e-11)
    return "shape=fallback arm=reuse residual=$residual orderingAttempts=${build.orderingAttempts} " +
        "reusedOrders=${build.reusedOrders} fallbacks=${build.fallbacks} buildUnits=${build.units} " +
        "buildNanos=$nanos buildBytes=$bytes"
}

private fun measureMeter(bean: ThreadMXBean): String {
    val thread = Thread.currentThread().threadId()
    val meter = BasisWorkMeter()
    val beforeBytes = bean.getThreadAllocatedBytes(thread)
    val beforeNanos = System.nanoTime()
    repeat(METER_UPDATES) {
        meter.updateAttempt()
        meter.updateSuccess(17)
    }
    val nanos = System.nanoTime() - beforeNanos
    val bytes = bean.getThreadAllocatedBytes(thread) - beforeBytes
    val work = meter.snapshot()
    meterSink = work
    return "shape=meter arm=accounting updates=$METER_UPDATES units=${work.update.units} " +
        "nanos=$nanos bytes=$bytes"
}

private fun checkBothDirections(solver: KotlinBasisSolver, source: SparseMatrix, basis: IntArray, step: Int): Double {
    val rhs = DoubleArray(DIMENSION) { i -> if (i == (step * 3 + 1) % DIMENSION) 1.0 else 0.0 }
    var residual = 0.0
    for (transpose in listOf(false, true)) {
        val vector = IndexedVector(DIMENSION).also { it.scatter(rhs) }
        if (transpose) solver.btran(vector, 0.0) else solver.ftran(vector, 0.0)
        residual = max(residual, ftResidual(source, basis, rhs, vector, transpose))
    }
    return residual
}

private const val DIMENSION = 16
private const val HEADING_CHANGES = 8
private const val SOLVES = 8
private const val UPDATES = 8
private const val WARMUPS = 1
private const val REPETITIONS = 3
private const val METER_UPDATES = 10_000
private val SHAPES = listOf("sparse", "spiked", "dense", "near")
