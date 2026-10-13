package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.util.concurrent.TimeUnit
import jdk.jfr.consumer.RecordingFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object ObjectiveFrontendProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            val parts = it.split('=', limit = 2)
            require(parts.size == 2) { "Use source=<fixture directory> [out=<directory>] [checker=none|provision]" }
            parts[0] to parts[1]
        }
        require(options.keys.all { it in setOf("source", "out", "checker") })
        val source = File(requireNotNull(options["source"]))
        val output = File(options["out"] ?: requireNotNull(System.getenv("OUT")))
        require(output.mkdirs() || output.isDirectory)
        val manifestBytes = File(source, "manifest.json").readBytes()
        val manifest = Json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject
        File(output, "manifest.json").writeBytes(manifestBytes)
        Reports.writeJson(File(output, "provenance.json").path, InstalledBuild.reference)
        File(output, "settings.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("seed", 17)
            put("processors", 1)
            put("nativeWork", 200000)
            put("maxFlips", 1)
            put("alnsIterations", 1)
            put("manifestSha256", sha256(manifestBytes))
        }.toString() + "\n")
        for (item in requireNotNull(manifest["cases"]).jsonArray) {
            val case = item.jsonObject
            fun field(name: String): String = requireNotNull(case[name]?.jsonPrimitive?.contentOrNull)
            val original = File(source, field("file"))
            val bytes = original.readBytes()
            require(sha256(bytes) == field("sha256")) { "Original source hash differs: $original" }
            val captured = File(output, original.name).apply { writeBytes(bytes) }
            for (engine in requireNotNull(case["engines"]).jsonArray) {
                val selected = requireNotNull(engine.jsonPrimitive.contentOrNull)
                require(selected in setOf("cp", "fixed", "ls", "alns", "mixed"))
                runCase(captured, output, "${field("family")}.$selected.${field("encoding")}", selected)
            }
        }
        when (options["checker"] ?: "none") {
            "none" -> Unit
            "provision" -> verify(output)
            else -> error("checker must be none or provision")
        }
    }

    private fun runCase(source: File, output: File, stem: String, engine: String) {
        val launcher = requireNotNull(System.getenv("KLAUSE_CLI"))
        val recording = File(output, "$stem.jfr")
        val command = listOf(launcher, "-s", "-e", engine, "-p", "1", "-r", "17",
            "--param", "max-flips=1", "--param", "alns-iterations=1", "--param", "nra-work-limit=200000",
            source.absolutePath)
        val builder = ProcessBuilder(command).redirectOutput(File(output, "$stem.stdout"))
            .redirectError(File(output, "$stem.stderr"))
        val options = "-XX:ActiveProcessorCount=1 -Xmx2g " +
            "-XX:StartFlightRecording=filename=${recording.absolutePath},settings=profile,dumponexit=true " +
            "-Xlog:jfr+startup=warning"
        builder.environment()["JAVA_OPTS"] = options
        builder.environment()["KLAUSE_CLI_OPTS"] = ""
        val started = System.nanoTime()
        val process = builder.start()
        val finished = process.waitFor(25, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        val exit = if (finished) process.exitValue().toString() else "timeout"
        val elapsed = System.nanoTime() - started
        File(output, "$stem.exit").writeText(exit + "\n")
        var actualOptions: String? = null
        if (recording.isFile) RecordingFile(recording.toPath()).use { reader ->
            while (reader.hasMoreEvents()) {
                val event = reader.readEvent()
                if (event.eventType.name == "jdk.JVMInformation") actualOptions = event.getString("jvmArguments")
            }
        }
        File(output, "$stem.launch.json").writeText(buildJsonObject {
            put("command", command.joinToString(" "))
            put("javaOptions", options)
            put("actualJvmArguments", actualOptions)
            put("elapsedNanos", elapsed)
            put("exit", exit)
        }.toString() + "\n")
        check(exit == "0") { "CLI failed for $stem: $exit" }
        check(actualOptions?.contains("-XX:ActiveProcessorCount=1") == true) { "CLI processor cap not retained" }
        println("$stem completed")
    }

    private fun verify(output: File) {
        val log = File(output, "checker-install.log")
        fun execute(file: File, vararg command: String) {
            val process = ProcessBuilder(command.toList()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(file)).start()
            check(process.waitFor() == 0) { "${command.first()} failed; see ${file.path}" }
        }
        execute(log, "sudo", "apt-get", "update")
        execute(log, "sudo", "apt-get", "install", "-y", "python3-venv")
        val environment = File(output, "checker-venv")
        require(!environment.exists())
        execute(log, "python3", "-m", "venv", environment.absolutePath)
        val python = File(environment, "bin/python").absolutePath
        execute(log, python, "-m", "pip", "install", "-r", "klause-bench/tools/requirements-source-checks.txt")
        execute(File(output, "checker-dependencies.txt"), python, "-m", "pip", "freeze")
        execute(File(output, "checker.log"), python,
            "klause-bench/tools/verify_objective_frontend.py", output.absolutePath)
        val tools = listOf("verify_objective_frontend.py", "verify_nra_source_results.py", "verify_source_results.py",
            "requirements-source-checks.txt")
        File(output, "checker-tool-hashes.json").writeText(buildJsonObject {
            for (tool in tools) put(tool, sha256(File("klause-bench/tools", tool).readBytes()))
        }.toString() + "\n")
        check(environment.deleteRecursively())
    }
}
