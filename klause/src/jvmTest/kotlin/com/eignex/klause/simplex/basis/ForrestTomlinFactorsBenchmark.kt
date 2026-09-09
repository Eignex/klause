package com.eignex.klause.simplex.basis

import com.eignex.koblas.hfactor.BundledHfactor
import com.eignex.koblas.sparse.basis.BasisSolver
import com.eignex.koblas.sparse.basis.BasisUpdate
import com.eignex.koblas.sparse.basis.IndexedVector
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.math.max
import kotlin.random.Random

// Explicit basis-only benchmark entry point; ordinary test discovery never runs measurements.
fun main(args: Array<String>) {
    val countsOnly = args.contentEquals(arrayOf("--counts-only"))
    require(args.isEmpty() || countsOnly)
    val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
    check(bean.isThreadAllocatedMemorySupported)
    bean.isThreadAllocatedMemoryEnabled = true
    for (shape in listOf("sparse", "spiked", "dense", "near", "dense16")) {
        for (rebuild in listOf(false, true)) {
            for (arm in listOf("ft", "eta", "hfactor")) {
                repeat(if (countsOnly) 0 else 8) { ftMeasure(shape, arm, rebuild, bean) }
            }
            repeat(if (countsOnly) 1 else 3) { repetition ->
                for (arm in listOf("ft", "eta", "hfactor")) {
                    val report = ftMeasure(shape, arm, rebuild, bean)
                    val fields = if (countsOnly) report.split(" ").filterNot {
                        it.substringBefore("=").endsWith("Bytes") || it.substringBefore("=").endsWith("Nanos") ||
                            it.startsWith("nanos=")
                    }.joinToString(" ") else report
                    println("B3bench shape=$shape arm=$arm rebuild=$rebuild repetition=$repetition $fields")
                }
            }
        }
    }
}

private fun ftMeasure(shape: String, arm: String, rebuild: Boolean, bean: ThreadMXBean): String {
    val thread = Thread.currentThread().threadId()
    val bytes = bean.getThreadAllocatedBytes(thread)
    val start = System.nanoTime()
    val n = if (shape == "dense16") 16 else 8
    val source = ftSource(if (shape == "dense16") "dense" else shape, n)
    val solver: BasisSolver = when (arm) {
        "ft" -> KotlinBasisSolver(source, updateLimit = 100)
        "eta" -> BasisEtaReference(source)
        else -> BundledHfactor().basisSolver(source)
    }
    val basis = IntArray(n) { n - 1 - it }
    val buildBefore = bean.getThreadAllocatedBytes(thread)
    val buildStart = System.nanoTime()
    check(solver.refactorize(basis))
    val buildNanos = System.nanoTime() - buildStart
    val buildBytes = bean.getThreadAllocatedBytes(thread) - buildBefore
    val random = Random(193)
    val vector = IndexedVector(n)
    val spike = IndexedVector(n)
    val pivotEta = IndexedVector(n)
    var residual = 0.0
    var arithmetic = 0L
    var updateProducts = 0L
    var copiedEntries = 0L
    var ftranBytes = 0L
    var btranBytes = 0L
    var ftranNanos = 0L
    var btranNanos = 0L
    var updateNanos = 0L
    var updateBytes = 0L
    var peakFill = solver.nnz
    var advice = 0
    var rebuilds = 0
    for (step in 0..24) {
        for (transpose in listOf(false, true)) {
            val rhs = DoubleArray(n) { if (it == step % n) 1.0 else 0.0 }
            vector.scatter(rhs)
            val before = bean.getThreadAllocatedBytes(thread)
            val operationStart = System.nanoTime()
            if (transpose) solver.btran(vector, 0.0) else solver.ftran(vector, 0.0)
            val nanos = System.nanoTime() - operationStart
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            if (transpose) {
                btranNanos += nanos
                btranBytes += allocated
            } else {
                ftranNanos += nanos
                ftranBytes += allocated
            }
            residual = max(residual, ftResidual(source, basis, rhs, vector, transpose))
            arithmetic += when (solver) {
                is KotlinBasisSolver -> solver.lastSolveWork!!.let {
                    it.first.arithmeticEntries + it.second.arithmeticEntries + it.transformEntries
                }
                is BasisEtaReference -> solver.solveEntries
                else -> 0
            }
        }
        if (step == 24) break
        val slot = if (step % 3 == 0) 2 else random.nextInt(n)
        val entering = (basis[slot] + n) % source.cols
        spike.scatterColumn(source, entering)
        solver.ftran(spike, 0.0)
        pivotEta.unit(slot)
        solver.btran(pivotEta, 0.0)
        val before = bean.getThreadAllocatedBytes(thread)
        val operationStart = System.nanoTime()
        val result = solver.update(slot, entering, spike, pivotEta)
        updateNanos += System.nanoTime() - operationStart
        updateBytes += bean.getThreadAllocatedBytes(thread) - before
        check(result != BasisUpdate.SINGULAR) { "$shape $arm step=$step declined" }
        if (result == BasisUpdate.REFACTORIZE) advice++
        if (solver is KotlinBasisSolver) {
            val work = solver.lastUpdateWork!!
            updateProducts += work.columnProducts + work.rowProducts
            copiedEntries += work.copiedEntries
        }
        basis[slot] = entering
        peakFill = max(peakFill, solver.nnz)
        if (rebuild && (step + 1) % 8 == 0) {
            check(solver.refactorize(basis))
            rebuilds++
        }
    }
    solver.close()
    check(residual <= if (shape == "near") 1e-8 else 1e-11) { "$shape $arm residual=$residual" }
    return "residual=$residual accepted=24 declined=0 advice=$advice rebuilds=$rebuilds " +
        "peakFill=$peakFill arithmetic=${if (arm == "hfactor") "unavailable" else arithmetic} " +
        "updateProducts=$updateProducts copiedEntries=$copiedEntries buildBytes=$buildBytes buildNanos=$buildNanos " +
        "ftranBytes=$ftranBytes ftranNanos=$ftranNanos btranBytes=$btranBytes btranNanos=$btranNanos " +
        "updateBytes=$updateBytes updateNanos=$updateNanos " +
        "totalBytes=${bean.getThreadAllocatedBytes(thread) - bytes} nanos=${System.nanoTime() - start}"
}
