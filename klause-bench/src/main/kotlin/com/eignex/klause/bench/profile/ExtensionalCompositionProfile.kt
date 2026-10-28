package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.Factor
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePipeline
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory

internal object ExtensionalCompositionProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.containsAll(setOf("suite", "problem", "sha256")))
        require(options.keys.all { it in setOf("suite", "problem", "sha256", "prepared", "preparationWork") })
        val preparationWorkLimit = options["preparationWork"]?.toLong() ?: 2_000_000_000L
        require(preparationWorkLimit > 0)
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use {
            it.readNBytes(64 * 1024 * 1024 + 1)
        }
        require(bytes.size <= 64 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("source.xml").writeBytes(bytes)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val source = Xcsp3.parse(bytes.decodeToString()).problem
        val preparationStart = System.nanoTime()
        var preparationWork = 0L
        val preparationMeter = WorkMeter { preparationWork += it }
        val preparationToken = object : Cancellation {
            override fun isCancelled(): Boolean = preparationWork >= preparationWorkLimit ||
                System.nanoTime() - preparationStart >= 90_000_000_000L
            override fun workMeter(): WorkMeter = preparationMeter
        }
        val preparation = if (options["prepared"] == "true") {
            PresolvePipeline.run(source, null, PresolveConfig.DEFAULT, true, preparationToken)
        } else null
        val problem = preparation?.problem ?: source
        out.resolve("preparation.json").writeText(buildJsonObject {
            put("requested", preparation != null)
            put("cancelled", preparationToken())
            put("work", preparationWork)
            put("workLimit", preparationWorkLimit)
            put("elapsedNs", System.nanoTime() - preparationStart)
            put("infeasible", preparation?.stats?.infeasible ?: false)
            put("sourceIntVars", source.numIntVars)
            put("preparedIntVars", problem.numIntVars)
            put("sourceFactors", source.factors.size)
            put("preparedFactors", problem.factors.size)
            put("allDifferent", problem.factors.count { it is AllDifferent })
        }.toString())
        if (preparationToken() || preparation?.stats?.infeasible == true) return
        val tables = problem.factors.filterIsInstance<Table>()
        val diagrams = problem.factors.filterIsInstance<Mdd>()
        val shapes = buildJsonObject {
            put("tables", buildJsonArray {
                tables.groupingBy { "${it.arity}/${it.numTuples}/${it.hi != null}" }.eachCount()
                    .forEach { (shape, count) ->
                        add(buildJsonObject { put("arity/rows/intervals", shape); put("count", count) })
                    }
            })
            put("tableScopes", buildJsonArray {
                for ((id, table) in tables.withIndex()) add(buildJsonObject {
                    put("table", id)
                    put("scope", table.xs.joinToString(","))
                    put("sourcePayloads", source.factors.indices.filter {
                        val original = source.factors[it] as? Table
                        original != null && original.tuples.contentEquals(table.tuples)
                    }.joinToString(","))
                })
            })
            put("tablePairs", buildJsonArray {
                for (a in tables.indices) for (b in a + 1 until tables.size) {
                    val shared = tables[a].xs.count { it in tables[b].xs }
                    if (shared == 0) continue
                    add(buildJsonObject { put("left", a); put("right", b); put("shared", shared) })
                }
            })
            put("allDifferentScopes", buildJsonArray {
                for (factor in problem.factors.filterIsInstance<AllDifferent>()) {
                    add(buildJsonObject { put("scope", factor.vars.joinToString(",")) })
                }
            })
            put("diagrams", buildJsonArray {
                diagrams.groupingBy { diagramShape(it) }
                    .eachCount().forEach { (shape, count) ->
                        add(buildJsonObject { put("arity/states/records", shape); put("count", count) })
                    }
            })
            put("overlaps", buildJsonArray {
                for (diagram in diagrams.take(1)) for (table in tables) {
                    val positions = diagram.seq.indices.filter { diagram.seq[it] in table.xs }
                    if (positions.size < 2) continue
                    add(buildJsonObject { put("positions", positions.joinToString(",")); put("rows", table.numTuples) })
                }
            })
        }
        out.resolve("shapes.json").writeText(shapes.toString())
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val samples = linkedMapOf("full" to problem.factors)
        diagrams.firstOrNull()?.let { diagram ->
            val overlaps = tables.filter { table -> diagram.seq.count { it in table.xs } >= 2 }
            for ((index, table) in overlaps.withIndex()) samples["overlap-$index"] = arrayOf<Factor>(diagram, table)
            samples["one-diagram"] = (listOf<Factor>(diagram) + overlaps).toTypedArray()
        }
        out.resolve("composition.jsonl").bufferedWriter().use { writer ->
            for ((name, factors) in samples) repeat(3) { iteration ->
                var work = 0L
                val meter = WorkMeter { work += it }
                val token = object : Cancellation {
                    override fun isCancelled(): Boolean = false
                    override fun workMeter(): WorkMeter = meter
                }
                val allocation = bean.getThreadAllocatedBytes(thread)
                val start = System.nanoTime()
                val delta = Presolve.composeExtensional(problem.withFactors(factors), token)
                val elapsed = System.nanoTime() - start
                val allocated = bean.getThreadAllocatedBytes(thread) - allocation
                val row = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("sourceSha256", sha256(bytes))
                    put("osName", System.getProperty("os.name"))
                    put("osArch", System.getProperty("os.arch"))
                    put("javaVersion", System.getProperty("java.version"))
                    put("sample", name)
                    put("iteration", iteration)
                    put("work", work)
                    put("elapsedNs", elapsed)
                    put("allocatedBytes", allocated)
                    put("replaced", delta.droppedIndices.size)
                    put("removedTableRows", delta.droppedIndices.sumOf { (factors[it] as? Table)?.numTuples ?: 0 } -
                        delta.addedFactors.filterIsInstance<Table>().sumOf { it.numTuples })
                    put("restrictions", buildJsonArray {
                        for (id in delta.droppedIndices) {
                            val before = factors[id] as? Table ?: continue
                            val after = delta.addedFactors.filterIsInstance<Table>()
                                .singleOrNull { it.xs.contentEquals(before.xs) } ?: continue
                            add(buildJsonObject {
                                put("factor", id)
                                put("scope", before.xs.joinToString(","))
                                put("beforeRows", before.numTuples)
                                put("afterRows", after.numTuples)
                                put("sourcePayloads", source.factors.indices.filter {
                                    val original = source.factors[it] as? Table
                                    original != null && original.tuples.contentEquals(before.tuples)
                                }.joinToString(","))
                            })
                        }
                    })
                    put("infeasible", delta.infeasible)
                    put("mddRecords", delta.addedFactors.filterIsInstance<Mdd>()
                        .sumOf { it.transitions.size / it.recordStride })
                    put("sourceVerdictClaim", false)
                }
                writer.appendLine(row.toString())
                writer.flush()
                println(row)
            }
        }
    }

    private fun diagramShape(mdd: Mdd): String =
        "${mdd.seq.size}/${mdd.numStatesPerLayer.sum()}/${mdd.transitions.size / mdd.recordStride}"
}
