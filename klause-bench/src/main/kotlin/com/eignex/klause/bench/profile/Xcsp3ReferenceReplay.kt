package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.Xcsp3CpSatReference
import com.eignex.klause.bench.metric.Xcsp3SourceValidation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object Xcsp3ReferenceReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2 && args[0].startsWith("request64="))
        require(args.size == 1 || args[1] in setOf("image=current", "mode=reduce"))
        val image = if (args.getOrNull(1) == "image=current") Xcsp3CpSatReference.IMAGE else "klause-xcsp3-cpsat:latest"
        val encoded = args[0].substringAfter('=')
        require(encoded.length <= 64 * 1024)
        val request = Base64.getDecoder().decode(encoded)
        val rows = Json.parseToJsonElement(request.decodeToString()).jsonArray
        require(rows.size == 1) { "Replay one source witness per bounded job" }
        val out = File(requireNotNull(System.getenv("OUT"))).resolve("xcsp3-reference-replay").apply { mkdirs() }
        out.resolve("request.json").writeBytes(request)
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        for ((index, value) in rows.withIndex()) {
            val row = value.jsonObject
            require(row.keys == setOf("suite", "problem", "sourceSha256", "witness", "objective"))
            require(row.getValue("suite").jsonPrimitive.content.startsWith("xcsp3-"))
            val ref = Catalog.uncapped(row.getValue("suite").jsonPrimitive.content).problems
                .single { it.name == row.getValue("problem").jsonPrimitive.content }
            val original = CorpusFiles.open(CorpusFetcher.resolve(ref.source)).use {
                it.readNBytes(32 * 1024 * 1024 + 1)
            }
            require(original.size <= 32 * 1024 * 1024 &&
                sha256(original) == row.getValue("sourceSha256").jsonPrimitive.content)
            out.resolve("$index.xml").writeBytes(original)
            val objective = row.getValue("objective").jsonPrimitive.contentOrNull
            val witness = row.getValue("witness").jsonPrimitive.content.removePrefix("v ")
            val rawOutput = objective?.let { "o $it\n" }.orEmpty() + "v $witness\n"
            Reports.writeJson(out.resolve("$index-source-check.json").path,
                Xcsp3SourceValidation.validate(ref, rawOutput, objective))
        }
        val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
        root.resolve("klause-bench/xcsp3-cpsat/diagnose.py").copyTo(out.resolve("diagnose.py"))
        val name = "xcsp3-reference-replay-${ProcessHandle.current().pid()}"
        val command = listOf("docker", "run", "--rm", "--name", name, "--network", "none", "--memory", "6g",
            "--memory-swap", "6g", "--cpus", "1", "--entrypoint", "python", "-v", "${out.absolutePath}:/out",
            image, "/out/diagnose.py", "/out") +
            if (args.getOrNull(1) == "mode=reduce") listOf("reduce") else emptyList()
        out.resolve("command.json").writeText(Reports.json.encodeToString(command))
        val process = ProcessBuilder(command).redirectOutput(out.resolve("stdout"))
            .redirectError(out.resolve("stderr")).start()
        try {
            check(process.waitFor(300, TimeUnit.SECONDS)) { "reference replay timed out" }
            out.resolve("exit").writeText("${process.exitValue()}\n")
            check(process.exitValue() == 0) { "reference replay failed" }
        } finally {
            if (process.isAlive) {
                ProcessBuilder("docker", "kill", name).redirectErrorStream(true)
                    .redirectOutput(out.resolve("cleanup.log")).start().waitFor(10, TimeUnit.SECONDS)
                process.destroyForcibly().waitFor()
            }
        }
    }
}
