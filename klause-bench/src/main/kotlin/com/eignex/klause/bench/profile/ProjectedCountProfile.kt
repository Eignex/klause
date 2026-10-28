package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.count.Count
import com.eignex.klause.count.ExactCountConfig
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ProjectedCountProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("pairs", "repeats", "maxChecks") })
        val sizes = (options["pairs"] ?: "2,4,8").split(',').map(String::toInt)
        require(sizes.isNotEmpty() && sizes.all { it in 2..16 })
        val repeats = (options["repeats"] ?: "3").toInt().also { require(it in 1..9) }
        val checks = (options["maxChecks"] ?: "10000").toLong().also { require(it in 1..1_000_000) }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        out.resolve("identity.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("javaVersion", System.getProperty("java.version"))
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("maxHeapBytes", Runtime.getRuntime().maxMemory())
            put("jvmOptions", buildJsonArray {
                ManagementFactory.getRuntimeMXBean().inputArguments.forEach { add(JsonPrimitive(it)) }
            })
        }.toString())
        val counter = Class.forName("com.eignex.klause.count.AnytimeCounter")
        val instance = counter.getField("INSTANCE").get(null)
        val ordinary = counter.methods.single { it.name.startsWith("monolithic") && it.parameterCount == 2 }
        val composed = counter.methods.single { it.name == "run" && it.parameterCount == 2 }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        out.resolve("counts.jsonl").bufferedWriter().use { output ->
            for ((pairs, connected) in sizes.map { it to false } + (sizes.max() to true)) {
                val source = source(pairs, connected)
                val name = "pairs-$pairs-${if (connected) "connected" else "disjoint"}"
                out.resolve("$name.cnf").writeText(source)
                val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                for (projectX in if (connected) listOf(false) else listOf(false, true)) {
                    val config = ExactCountConfig(
                        samplingSet = if (projectX) IntArray(pairs) { it * 2 } else null,
                        maxChecks = checks,
                        maxDecisionsPerCheck = 100L,
                    )
                    var expected: Count? = null
                    for (repeat in -1 until repeats) {
                        for (componentPath in if (repeat % 2 == 0) listOf(true, false) else listOf(false, true)) {
                            val allocationStart = bean.getThreadAllocatedBytes(thread)
                            val start = System.nanoTime()
                            val problem: BakedProblem = Dimacs.parse(source).toProblem().bake()
                            val prepared = System.nanoTime()
                            val method = if (componentPath) composed else ordinary
                            val result = (method.invoke(instance, problem, config) as Sequence<*>).last() as Count
                            val end = System.nanoTime()
                            val bytes = bean.getThreadAllocatedBytes(thread) - allocationStart
                            expected?.let { check(it == result) { "$name projectX=$projectX: $it != $result" } }
                            expected = result
                            if (repeat < 0) continue
                            val record = buildJsonObject {
                                put("source", name)
                                put("sha256", hash)
                                put("pairs", pairs)
                                put("connected", connected)
                                put("projection", if (projectX) "x" else "all")
                                put("path", if (componentPath) "components" else "monolithic")
                                put("repeat", repeat)
                                put("maxChecks", checks)
                                put("maxDecisionsPerCheck", config.maxDecisionsPerCheck)
                                put("constructionNs", prepared - start)
                                put("countNs", end - prepared)
                                put("totalNs", end - start)
                                put("threadAllocatedBytes", bytes)
                                put("lower", result.lower)
                                put("upper", result.upper)
                                put("exact", result.exact)
                            }
                            output.appendLine(record.toString())
                            output.flush()
                            println(record)
                        }
                    }
                }
            }
        }
    }

    private fun source(pairs: Int, connected: Boolean): String = buildString {
        appendLine("p cnf ${pairs * 2} ${pairs * 2 + if (connected) pairs - 1 else 0}")
        for (i in 0 until pairs) {
            val x = i * 2 + 1
            val y = x + 1
            appendLine("$x $y 0")
            appendLine("-${x} -${y} 0")
        }
        if (connected) for (i in 1 until pairs) appendLine("1 ${i * 2 + 1} 0")
    }
}
