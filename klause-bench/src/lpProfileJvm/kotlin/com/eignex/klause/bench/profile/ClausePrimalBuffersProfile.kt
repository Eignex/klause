package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.ClausePrimalWalk
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Allocation and latency of production clausal primal walk construction and repair trials. */
object ClausePrimalBuffersProfile {
    @Volatile private var consumed = 0

    /** Run a deterministic fixture and retain measured samples outside the timed sections. */
    @JvmStatic
    fun main(args: Array<String>) {
        val groups = args[0].toInt().also { require(it in 2..22_000) }
        val costCount = args[1].toInt().also { require(it in setOf(2, 64)) }
        val seed = args[2].toLong()
        val output = Path.of(args[3])
        val fixture = Fixture(groups, costCount, seed)
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: error("Allocation counter unavailable")
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        check(bean.getThreadAllocatedBytes(thread) >= 0L)
        val until = System.nanoTime() + 1_000_000_000L
        do { fixture.run(1, bean, thread) } while (System.nanoTime() < until)
        val sessions = (524_288 / (groups * 3)).coerceIn(8, 8192)
        val samples = List(7) { fixture.run(sessions, bean, thread) }
        fun median(field: (Observation) -> Double) = samples.map(field).sorted()[samples.size / 2]
        val result = buildJsonObject {
            put("scope", "Production clausal primal walk microbenchmark; no whole-solver throughput claim")
            put("groups", groups)
            put("variables", groups * 3)
            put("clauses", groups * 2)
            put("costs", fixture.costs.size)
            put("seed", seed)
            put("java", System.getProperty("java.runtime.version"))
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
            put("constructionBytes", median { it.constructionBytes.toDouble() / sessions })
            put("trialBytes", median { it.trialBytes.toDouble() / it.trials })
            put("constructionNs", median { it.constructionNs.toDouble() / sessions })
            put("trialNs", median { it.trialNs.toDouble() / it.trials })
            put("samples", JsonArray(samples.map { row -> buildJsonObject {
                put("sessions", sessions)
                put("trials", row.trials)
                put("constructionBytes", row.constructionBytes)
                put("trialBytes", row.trialBytes)
                put("constructionNs", row.constructionNs)
                put("trialNs", row.trialNs)
                put("work", row.work)
                put("checksum", row.checksum)
            } }))
        }
        Files.createDirectories(output.toAbsolutePath().parent)
        Files.writeString(output, "$result\n")
        println(result)
    }

    private class Observation {
        var constructionBytes = 0L
        var trialBytes = 0L
        var constructionNs = 0L
        var trialNs = 0L
        var trials = 0L
        var work = 0L
        var checksum = 0
    }

    private class Fixture(val groups: Int, costCount: Int, val seed: Long) {
        val costs = List(minOf(groups, costCount)) { it * (groups - 1) / (minOf(groups, costCount) - 1) * 3 }
        private val trialVariables = costs.take(32)
        val clauses = (0 until groups).flatMap { group ->
            val a = group * 3
            listOf(Clause(intArrayOf(Lit.make(a, true), Lit.make(a + 1, true), Lit.make(a + 2, true))),
                Clause(intArrayOf(Lit.make(a + 1, false), Lit.make(a + 2, false))))
        }

        fun run(sessions: Int, bean: ThreadMXBean, thread: Long): Observation {
            val result = Observation()
            repeat(sessions) {
                var bytes = bean.getThreadAllocatedBytes(thread)
                var started = System.nanoTime()
                val walk = ClausePrimalWalk(Sample(BooleanArray(groups * 3) { it % 3 == 0 }, longArrayOf()),
                    clauses, costs, Assumptions.None, seed, null)
                result.constructionNs += System.nanoTime() - started
                result.constructionBytes += bean.getThreadAllocatedBytes(thread) - bytes
                val work = walk.work
                bytes = bean.getThreadAllocatedBytes(thread)
                started = System.nanoTime()
                for (variable in trialVariables) {
                    walk.begin(variable, Cancellation.Never)
                    check(walk.advance(Cancellation.Never, -1L) == true)
                    result.trials++
                }
                result.trialNs += System.nanoTime() - started
                result.trialBytes += bean.getThreadAllocatedBytes(thread) - bytes
                result.work += walk.work - work
                val sample = walk.sample()
                check(trialVariables.all { !sample.bools[it] })
                check(clauses.all { clause -> clause.literals.any { Lit.evaluate(it, sample.bools[Lit.variable(it)]) } })
                result.checksum = 31 * result.checksum + sample.bools.contentHashCode()
                consumed = result.checksum
            }
            return result
        }
    }
}
