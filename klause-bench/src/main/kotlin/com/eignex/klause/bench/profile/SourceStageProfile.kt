package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.bench.tools.CliProfile
import java.io.File
import java.io.PrintStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object SourceStageProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args.single().startsWith("request64="))
        val bytes = Base64.getDecoder().decode(args.single().substringAfter('='))
        require(bytes.size <= 48 * 1024)
        val requests = Reports.json.parseToJsonElement(bytes.decodeToString()).jsonArray
        require(requests.size in 1..8)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.json").writeBytes(bytes)
        requests.forEachIndexed { index, value ->
            val input = value.jsonObject
            val directory = out.resolve("$index").apply { mkdirs() }
            if ("job" in input) summarize(input, directory) else capture(input, directory)
        }
    }

    private fun summarize(input: JsonObject, out: File) {
        require(input.keys - "recording" == setOf("job", "sha256"))
        val job = input.getValue("job").jsonPrimitive.content.toLong().also { require(it > 0) }
        val jobs = File(requireNotNull(System.getenv("JOB_DIR"))).canonicalFile.parentFile
        val relative = input["recording"]?.jsonPrimitive?.content ?: "profile.jfr"
        require(Regex("([0-7]/)?(cli|profile)\\.jfr").matches(relative))
        val recording = jobs.resolve("$job/cases/0/out/$relative").canonicalFile
        require(recording.toPath().startsWith(jobs.toPath()))
        require(recording.length() in 1..128L * 1024 * 1024)
        val hash = sha256(recording.readBytes())
        require(hash == input.getValue("sha256").jsonPrimitive.content)
        out.resolve("identity.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceJob", job)
            put("recording", relative)
            put("sha256", hash)
            put("solverExecuted", false)
        }.toString())
        PrintStream(out.resolve("profile-summary.txt")).use {
            CliProfile.summarizeCli(recording, it, "retained profile $job")
        }
    }

    private fun capture(input: JsonObject, out: File) {
        require(input.keys == setOf("suite", "problem", "sha256", "engine", "exact"))
        val reference = Catalog.uncapped(input.getValue("suite").jsonPrimitive.content).problems
            .single { it.name == input.getValue("problem").jsonPrimitive.content }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use {
            it.readNBytes(16 * 1024 * 1024 + 1)
        }
        require(bytes.size <= 16 * 1024 * 1024 && sha256(bytes) == input.getValue("sha256").jsonPrimitive.content)
        val source = out.resolve("source.mps").apply { writeBytes(bytes) }
        val engine = input.getValue("engine").jsonPrimitive.content
        require(engine in setOf("default", "cp", "ls"))
        val exact = input.getValue("exact").jsonPrimitive.content.toBooleanStrict()
        val recording = out.resolve("cli.jfr")
        val options = "-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1 " +
            "-XX:StartFlightRecording=filename=$recording,settings=profile,dumponexit=true"
        val command = buildList {
            add(SolverInvocation.klauseCliBin().absolutePath)
            addAll(listOf("--format", "mps", source.absolutePath, "-t", "10000", "-r", "3", "-s",
                "-a", "--output-objective"))
            if (engine != "default") addAll(listOf("-e", engine))
            if (exact) add("--exact")
        }
        out.resolve("identity.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", sha256(bytes))
            put("command", JsonArray(command.map(::JsonPrimitive)))
            put("cliOptions", options)
            put("debug", true)
            put("sourceVerdictClaim", false)
            put("purpose", "stage attribution under JFR; no throughput claim")
        }.toString())
        val process = ProcessBuilder(command).redirectOutput(out.resolve("cli.stdout"))
            .redirectError(out.resolve("cli.stderr")).apply {
                environment().apply {
                    put("KLAUSE_CLI_OPTS", options)
                    put("KLAUSE_DEBUG", "true")
                    for (name in listOf("OPENBLAS_NUM_THREADS", "VECLIB_MAXIMUM_THREADS", "MKL_NUM_THREADS",
                        "OMP_NUM_THREADS")) put(name, "1")
                }
            }.start()
        val completed = process.waitFor(45, TimeUnit.SECONDS)
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor()
        }
        out.resolve("cli.exit").writeText("${process.exitValue()}\n")
        check(completed && process.exitValue() == 0) { "CLI profile did not finish successfully" }
        PrintStream(out.resolve("profile-summary.txt")).use { CliProfile.summarizeCli(recording, it) }
    }
}
