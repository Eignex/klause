package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.xcsp3.Xcsp3
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

internal object DivModConstructionProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys - setOf("sizes", "repeats", "warmups", "suite", "problem", "sha256") == emptySet<String>())
        val sizes = (options["sizes"] ?: "1024,2048,4096,8192").split(',').map(String::toInt)
        require(sizes.size in 1..8 && sizes.all { it in 1..32768 })
        val repeats = (options["repeats"] ?: "3").toInt().also { require(it in 1..7) }
        val warmups = (options["warmups"] ?: "1").toInt().also { require(it in 0..3) }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        val sources = sizes.map { size -> "repeated-$size" to repeatedSource(size) }.toMutableList()
        if ("suite" in options || "problem" in options || "sha256" in options) {
            val reference = Catalog.uncapped(options.getValue("suite")).problems
                .single { it.name == options.getValue("problem") }
            val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use {
                it.readNBytes(16 * 1024 * 1024 + 1)
            }
            require(bytes.size <= 16 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
            sources += "catalog" to bytes.decodeToString()
        }
        val observations = out.resolve("construction.jsonl")
        observations.writeText("")
        for ((name, text) in sources) {
            val bytes = text.encodeToByteArray()
            out.resolve("$name.xml").writeBytes(bytes)
            repeat(warmups + repeats) { iteration ->
                val started = System.nanoTime()
                val parsed = Xcsp3.parse(text)
                val elapsed = System.nanoTime() - started
                val problem = parsed.problem
                val observation = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("name", name)
                    put("sourceSha256", sha256(bytes))
                    put("sourceBytes", bytes.size)
                    put("iteration", iteration - warmups)
                    put("warmup", iteration < warmups)
                    put("constructionNs", elapsed)
                    put("solverExecuted", false)
                    put("sourceVerdictClaim", false)
                    put("osName", System.getProperty("os.name"))
                    put("osArch", System.getProperty("os.arch"))
                    put("javaVersion", System.getProperty("java.version"))
                    put("processors", Runtime.getRuntime().availableProcessors())
                    put("booleans", problem.numBoolVars)
                    put("integers", problem.numIntVars)
                    put("factors", problem.factors.size)
                    put(
                        "factorKinds",
                        buildJsonObject {
                            problem.factors.groupingBy { it::class.simpleName ?: "Factor" }.eachCount().toSortedMap()
                                .forEach { (kind, count) -> put(kind, count) }
                        },
                    )
                }
                observations.appendText("$observation\n")
                println(observation)
            }
        }
    }

    private fun repeatedSource(size: Int): String = buildString {
        append("<instance><variables>")
        repeat(size) { append("<var id=\"x$it\">-7..7</var>") }
        append("</variables><constraints>")
        repeat(2) {
            repeat(size) { index -> append("<intension>eq(mod(x$index,3),1)</intension>") }
        }
        append("</constraints></instance>")
    }
}
