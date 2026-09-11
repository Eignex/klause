package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisTraceCodec
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Path

/** Explicit composed pricing probe; model construction and source capture remain outside timing. */
@Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
fun main(args: Array<String>) {
    val bean = (ManagementFactory.getThreadMXBean() as ThreadMXBean).also {
        check(it.isThreadAllocatedMemorySupported)
        it.isThreadAllocatedMemoryEnabled = true
    }
    val thread = Thread.currentThread().threadId()
    for (id in listOf("mps-adlittle", "mps-afiro")) {
        val trace = BasisTraceCodec.read(Path.of(args[0], "$id.kbtrace"))
        val source = trace.matrix
        val pointers = source.copyColumnPointers()
        val rows = source.copyRowIndices()
        val rhs = DoubleArray(source.rows) { 1.0 }
        for (zeroCost in listOf(false, true)) {
            val builder = LpBuilder()
            val variables = IntArray(source.columns) { j ->
                builder.addRealVar(0.0, 10.0, if (zeroCost) 0.0 else (j % 5 + 1).toDouble())
            }
            for (i in 0 until source.rows) {
                val columns = ArrayList<Int>()
                val values = ArrayList<Double>()
                for (j in variables.indices) {
                    for (entry in pointers[j] until pointers[j + 1]) {
                        if (rows[entry] == i) {
                            columns.add(variables[j])
                            values.add(kotlin.math.abs(source.valueAt(entry)))
                        }
                    }
                }
                builder.addRealRow(columns.toIntArray(), values.toDoubleArray(), Relation.GE, rhs[i])
            }
            val model = builder.build(Sense.MINIMIZE)
            repeat(5) { RevisedSimplex(model).close() }
            val setupBefore = bean.getThreadAllocatedBytes(thread)
            val owner = RevisedSimplex(model)
            val setupBytes = bean.getThreadAllocatedBytes(thread) - setupBefore
            println("pricing-setup\t$id\t$zeroCost\t$setupBytes")
            owner.use { solver ->
                repeat(60) { solver.solve() }
                var bytes = 0L
                var nanos = 0L
                var pivots = 0L
                var work = 0L
                var samples = 0L
                var results = 0
                repeat(30) {
                    val before = bean.getThreadAllocatedBytes(thread)
                    val start = System.nanoTime()
                    val result = solver.solve()
                    nanos += System.nanoTime() - start
                    bytes += bean.getThreadAllocatedBytes(thread) - before
                    pivots += result?.pivots ?: 0
                    work += solver.lastWorkOps
                    samples += solver.lastTheoryPricingSamples
                    check(result != null && result.optimal)
                    run {
                        results++
                        val product = DoubleArray(source.rows)
                        for (j in variables.indices) {
                            check(result.primal[j] >= -1e-6 && result.primal[j].isFinite())
                            for (entry in pointers[j] until pointers[j + 1]) {
                                product[rows[entry]] += kotlin.math.abs(source.valueAt(entry)) * result.primal[j]
                            }
                        }
                        for (i in product.indices) check(product[i] >= rhs[i] - 1e-6)
                    }
                }
                println("pricing\t$id\t$zeroCost\t${source.rows}\t${source.columns}\t${bytes / 30.0}\t${nanos / 30.0}\t$pivots\t$work\t$samples\t$results")
            }
        }
    }
}
