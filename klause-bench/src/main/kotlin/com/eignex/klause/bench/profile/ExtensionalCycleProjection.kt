package com.eignex.klause.bench.profile

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory

internal object ExtensionalCycleProjection {
    class Projection(val source: Table, val table: Table, val counts: Map<Pair<Long, Long>, Int>)

    fun capture(problem: Problem, sourceHash: String, out: File) {
        val tables = problem.factors.filterIsInstance<Table>()
        if (tables.size < 3) return
        val last = tables.lastIndex
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val clusters = listOf(listOf(0, 1, 2), listOf(last - 2, last - 1, last)).distinct()
        var work = 0L
        val meter = WorkMeter { work += it }
        val start = System.nanoTime()
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 8_388_608 || System.nanoTime() - start >= 60_000_000_000L
            override fun workMeter(): WorkMeter = meter
        }
        fun visit(): Boolean {
            if (token()) return false
            meter.charge(1)
            return !token()
        }
        out.resolve("cycles.jsonl").bufferedWriter().use { writer ->
            for (cluster in clusters) {
                val sources = cluster.map { tables[it] }
                if (sources.any { it.hi != null || it.xs.toSet().size != it.arity }) continue
                val columns = sources.map { source ->
                    source.xs.indices.filter { column -> sources.any { it !== source && source.xs[column] in it.xs } }
                }
                if (columns.any { it.size != 2 } || sources.indices.any { a ->
                    (a + 1 until sources.size).any { b -> sources[a].xs.count { it in sources[b].xs } != 1 }
                }) continue
                val beforeWork = work
                val beforeTime = System.nanoTime()
                val beforeAllocation = bean.getThreadAllocatedBytes(thread)
                val projections = sources.indices.map { i ->
                    project(sources[i], columns[i], Array(sources[i].arity) {
                        problem.intDomainOrNull(sources[i].xs[it])
                    }, ::visit)
                }
                val ready = projections.filterNotNull()
                val delta = if (ready.size == 3) {
                    Presolve.composeExtensional(problem.withFactors(ready.map { it.table }.toTypedArray()), token)
                } else null
                val consumed = if (delta != null && !token()) ready.map { projection ->
                    val after = delta.addedFactors.filterIsInstance<Table>()
                        .singleOrNull { it.xs.contentEquals(projection.table.xs) } ?: projection.table
                    if (delta.infeasible) projection.counts.values.sum()
                    else countUnsupported(projection, after, ::visit)
                } else emptyList()
                val row = buildJsonObject {
                    put("sourceSha256", sourceHash)
                    put("cluster", cluster.joinToString(","))
                    put("complete", delta != null && consumed.size == 3 && consumed.all { it != null })
                    put("cancelled", token())
                    put("work", work - beforeWork)
                    put("cumulativeWork", work)
                    put("elapsedNs", System.nanoTime() - beforeTime)
                    put("allocatedBytes", bean.getThreadAllocatedBytes(thread) - beforeAllocation)
                    put("sourceRows", sources.joinToString(",") { it.numTuples.toString() })
                    put("rootFeasibleRows", ready.joinToString(",") { it.counts.values.sum().toString() })
                    put("projectedRows", ready.joinToString(",") { it.table.numTuples.toString() })
                    put("removedSourceRows", consumed.joinToString(","))
                    put("replacedProjections", delta?.droppedIndices?.size ?: 0)
                    put("infeasible", delta?.infeasible ?: false)
                    put("privateAllDifferentColumns", buildJsonArray {
                        for (projection in ready) add(buildJsonObject {
                            put("sourceScope", projection.source.xs.joinToString(","))
                            put("projectedScope", projection.table.xs.joinToString(","))
                            put("scope", projection.source.xs.filter { variable ->
                                variable !in projection.table.xs && problem.factors.filterIsInstance<AllDifferent>()
                                    .any { variable in it.vars }
                            }.joinToString(","))
                        })
                    })
                    put("sourceVerdictClaim", false)
                }
                writer.appendLine(row.toString())
                writer.flush()
                println(row)
                if (token()) break
            }
        }
    }

    fun project(source: Table, columns: List<Int>, domains: Array<IntDomain?>, visit: () -> Boolean): Projection? {
        var work = 0
        fun step(): Boolean = work++ < 1_500_000 && visit()
        val pairs = LinkedHashMap<Pair<Long, Long>, Int>()
        for (row in 0 until source.numTuples) {
            var feasible = true
            for (column in source.xs.indices) {
                if (!step()) return null
                val domain = domains[column]
                if (domain != null && source.tuples[row * source.arity + column] !in domain) {
                    feasible = false
                    break
                }
            }
            if (!feasible) continue
            val pair = source.tuples[row * source.arity + columns[0]] to source.tuples[row * source.arity + columns[1]]
            val count = pairs[pair]
            if (count == null && pairs.size >= 13_107) return null
            pairs[pair] = (count ?: 0) + 1
        }
        if (pairs.isEmpty()) return null
        val tuples = LongArray(pairs.size * 2)
        for ((row, pair) in pairs.keys.withIndex()) {
            if (!step() || !step()) return null
            tuples[row * 2] = pair.first
            tuples[row * 2 + 1] = pair.second
        }
        return Projection(source, Table(columns.map { source.xs[it] }.toIntArray(), tuples), pairs)
    }

    fun countUnsupported(projection: Projection, allowed: Table, visit: () -> Boolean): Int? {
        val pairs = HashSet<Pair<Long, Long>>()
        for (row in 0 until allowed.numTuples) {
            if (!visit() || !visit()) return null
            pairs.add(allowed.tuples[row * 2] to allowed.tuples[row * 2 + 1])
        }
        var removed = 0
        for ((pair, count) in projection.counts) {
            if (!visit() || !visit()) return null
            if (pair !in pairs) removed += count
        }
        return removed
    }
}
