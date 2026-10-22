package com.eignex.klause.bench.profile

import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import java.io.File
import java.lang.management.ManagementFactory

/** Measures current-thread allocations of the production table reduction hook in a lab profile job. */
object TableReductionProfile {
    @Volatile
    private var sink: FactorReduction = FactorReduction.Unchanged

    /** Writes unchanged, half-pruned and over-cap controls to `$OUT/table-reduction.csv`. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "TableReductionProfile takes no arguments" }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val out = File(System.getenv("OUT") ?: "build/profile-out").apply { mkdirs() }
        File(out, "table-reduction.csv").bufferedWriter().use { writer ->
            writer.appendLine("arity,tuples,pruned,batch,iterations,allocatedBytes,outcome,survivors")
            for (arity in listOf(4, 64)) {
                for (rows in listOf(64, 4096, 4097)) {
                    val table = Table(IntArray(arity) { it }, LongArray(arity * rows) { (it / arity).toLong() })
                    for (pruned in listOf(false, true)) {
                        val domains = Array(arity) { IntDomain(0, (rows - 1).toLong()) }
                        if (pruned) domains[0] = IntDomain(0, (rows / 2 - 1).toLong())
                        repeat(100) { sink = table.structuralReduce(domains) }
                        repeat(10) { batch ->
                            val before = bean.getThreadAllocatedBytes(thread)
                            repeat(30) { sink = table.structuralReduce(domains) }
                            val bytes = bean.getThreadAllocatedBytes(thread) - before
                            check(before >= 0 && bytes >= 0)
                            val result = sink
                            val retained = if (result is FactorReduction.Rewrite) {
                                (result.replacement.single() as Table).numTuples
                            } else {
                                rows
                            }
                            writer.appendLine(
                                "$arity,$rows,$pruned,$batch,30,$bytes," +
                                    "${if (result is FactorReduction.Rewrite) "rewrite" else "unchanged"},$retained",
                            )
                            writer.flush()
                        }
                    }
                }
            }
        }
    }
}
