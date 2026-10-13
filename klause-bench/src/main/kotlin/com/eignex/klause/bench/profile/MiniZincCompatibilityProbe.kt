package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolveEvidence
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.runner.MiniZincCompileFailure
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.target.BenchCli
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintStream

internal object MiniZincCompatibilityProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val names = requireNotNull(options["names"]) { "names=<comma-separated original identities>" }.split(',')
        val out = File(requireNotNull(System.getenv("OUT")))
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.current)
        Reports.writeJson(out.resolve("compiler.json").path, MiniZincCompatibility.compiler)
        val all = Catalog.uncapped("mzn-bench-compatible-v1").problems.associateBy { it.name }
        val families = options["families"]?.split(',').orEmpty()
        val refs = (names.map { requireNotNull(all[it]) { "unknown source $it" } } +
            all.values.filter { it.family in families }).distinctBy { it.name }
        require(refs.size <= 128) { "at most 128 source identities per audit" }
        val auditedNames = refs.map { it.name }
        val repetitions = options["repeats"]?.toInt() ?: 3
        require(repetitions in 1..10)
        val failures = linkedMapOf<String, MiniZincCompileFailure>()
        val receipts = buildJsonArray {
            repeat(repetitions) { repetition ->
                for (ref in if (repetition % 2 == 0) refs else refs.reversed()) {
                    val hashes = SolveEvidence.sourceHashes(ref)
                    val directory = out.resolve("sources/${ref.name}").apply { mkdirs() }
                    val model = CorpusFetcher.resolve(ref.source)
                    model.copyTo(directory.resolve(model.name), overwrite = true)
                    ref.data?.let { CorpusFetcher.resolve(it).let { data ->
                        data.copyTo(directory.resolve(data.name), overwrite = true)
                    } }
                    model.parentFile.walkTopDown().filter { it.isFile && it.extension == "mzn" }.forEach {
                        val target = directory.resolve("includes/${it.relativeTo(model.parentFile).path}")
                        target.parentFile.mkdirs()
                        it.copyTo(target, overwrite = true)
                    }
                    val start = System.nanoTime()
                    val failure = MiniZincCompatibility.exclusion(ref)
                    if (failure != null) failures[ref.name] = failure
                    check(hashes == SolveEvidence.sourceHashes(ref)) { "source changed during compilation" }
                    add(buildJsonObject {
                        put("problem", ref.name)
                        put("repetition", repetition)
                        put("elapsedNs", System.nanoTime() - start)
                        put("compilerExit", failure?.exitCode ?: 0)
                        put("compilerCommand", Reports.json.encodeToJsonElement(failure?.command))
                        put("compilerElapsedNs", failure?.elapsedNs)
                        put("status", if (failure == null) "compilable" else "source-compiler-incompatible")
                        put("reason", failure?.incompatibility)
                        put("diagnostic", failure?.diagnostic)
                        put("sourceHashes", Reports.json.encodeToJsonElement(hashes))
                        put("solverInvocations", 0)
                    })
                }
            }
        }
        out.resolve("compilations.json").writeText(receipts.toString() + "\n")
        val exclusions = PrintStream(out.resolve("exclusions.jsonl"))
        val original = System.err
        try {
            System.setErr(exclusions)
            val selections = buildJsonArray {
                for (seed in listOf(null, "1", "7")) {
                    val filters = buildMap {
                        put("name", auditedNames.joinToString(","))
                        put("per-family", options["per-family"] ?: "1")
                        put("max", options["max"] ?: "100")
                        if (seed != null) put("seed", seed)
                    }
                    for (suite in listOf("mzn-bench", "mzn-bench-compatible-v1")) {
                        val start = System.nanoTime()
                        val selected = BenchCli.selectProblems(filters + ("suite" to suite))
                        add(buildJsonObject {
                            put("suite", suite)
                            put("seed", seed)
                            put("elapsedNs", System.nanoTime() - start)
                            put("filters", Reports.json.encodeToJsonElement(filters))
                            put("identities", buildJsonArray { selected.forEach { add(JsonPrimitive(it.ref.name)) } })
                        })
                        if (suite == "mzn-bench-compatible-v1") check(selected.none { it.ref.name in failures })
                    }
                }
            }
            out.resolve("selections.json").writeText(selections.toString() + "\n")
        } finally {
            System.setErr(original)
            exclusions.close()
        }
        val frozen = options["set"]?.let { set ->
            BenchCli.selectProblems(mapOf("set" to set, "name" to auditedNames.joinToString(",")))
                .map { "${it.suite}/${it.ref.name}" }
        }.orEmpty()
        out.resolve("summary.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("requested", refs.size)
            put("incompatible", failures.size)
            put("compilable", refs.size - failures.size)
            put("solverInvocations", 0)
            put("repeats", repetitions)
            put("frozenSet", options["set"])
            put("frozenMembers", buildJsonArray { frozen.forEach { add(JsonPrimitive(it)) } })
        }.toString() + "\n")
        println(out.resolve("summary.json").readText())
    }
}
