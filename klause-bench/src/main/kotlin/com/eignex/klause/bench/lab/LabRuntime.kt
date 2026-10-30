package com.eignex.klause.bench.lab

import com.eignex.lab.bench.*
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path

internal object LabRuntime {
    fun environment(case: BenchCase, context: BenchContext): Map<String, String> {
        val p = case.parameters
        val runtime = buildList {
            add("-Xmx${p.getValue("heap-mib")}m")
            add("-XX:ActiveProcessorCount=${case.resources.cpus}")
            when (p.getValue("gc")) {
                "serial" -> add("-XX:+UseSerialGC")
                "parallel" -> add("-XX:+UseParallelGC")
                "g1" -> add("-XX:+UseG1GC")
                "z" -> add("-XX:+UseZGC")
            }
        }
        val report = context.outputDirectory.resolve("report")
        val measurement = p.getValue("measurement")
        val recordsJfr = measurement in setOf("jfr", "compilation", "compilation+log")
        val options = runtime + buildList {
            if (recordsJfr) {
                val compilation = if (measurement == "jfr") "" else ",jdk.Compilation#threshold=0ms"
                add("-XX:StartFlightRecording=filename=$report/cli.jfr,settings=profile$compilation,dumponexit=true")
            }
            if (measurement == "compilation+log") {
                add("-XX:+UnlockDiagnosticVMOptions")
                add("-XX:+LogCompilation")
                add("-XX:LogFile=$report/hotspot-compilation.log")
            }
        }
        fun argumentFile(name: String, values: List<String>): String {
            val path = context.outputDirectory.resolve(name)
            Files.writeString(path, values.joinToString("\n") { quote(it) } + "\n")
            return quote("@$path")
        }
        Files.writeString(
            context.outputDirectory.resolve("runtime.json"),
            buildJsonObject {
                put("scope", "whole-cli-jvm")
                put("measurement", measurement)
                put("debug", p.getValue("debug"))
                put("memoryMiB", case.resources.memoryMiB)
                put("cpus", case.resources.cpus)
                put("timeoutSeconds", case.resources.timeoutSeconds)
                put("solverBudgetMs", p.getValue("timeout-ms"))
                put("compilerSeed", p["compiler-seed"] ?: "1")
                put("compilerTimeoutSeconds", p.getValue("compile-timeout-sec"))
                put(
                    "deadlinePolicy",
                    "cli-inclusive-v1; compiler/preflight/checking outside CLI deadline, inside case/job ceilings",
                )
                put("resourceScope", "GNU time CLI process tree; peak RSS in KiB")
                put("validationPolicy", "reported-result-v1")
                putJsonArray("javaOptions") { options.forEach { add(it) } }
                putJsonArray("metricSources") {
                    add("solve-record.json")
                    if (measurement != "none") add("resources.txt")
                }
            }.toString(),
        )
        return mapOf(
            "JAVA_OPTS" to "",
            "JAVA_HOME" to System.getProperty("java.home"),
            "KLAUSE_DEBUG" to p.getValue("debug"),
            "KLAUSE_CLI_OPTS" to argumentFile("cli.args", options),
            "KLAUSE_CLI_PREFLIGHT_OPTS" to argumentFile("preflight.args", runtime),
            "LAB_WORKTREE" to requireNotNull(context.worktree).toString(),
        ) +
            context.corpusDirectory?.let { mapOf("LAB_CORPUS" to it.toString()) }.orEmpty() +
            (if (recordsJfr) mapOf("KLAUSE_LAB_PROFILE_DIR" to report.toString()) else emptyMap())
    }

    fun process(context: BenchContext, argv: List<String>, environment: Map<String, String>): Int {
        val process = ProcessBuilder(argv).directory(requireNotNull(context.worktree).toFile())
            .redirectErrorStream(true).redirectOutput(context.outputDirectory.resolve("solve.log").toFile()).apply {
                environment().keys.removeAll { key ->
                    key.startsWith("KLAUSE_") || key.startsWith("EIGNEX_LAB_") || key.startsWith("MZN_") ||
                        key.startsWith("MINIZINC_") || key in
                        setOf("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")
                }
                environment().putAll(environment)
            }.start()
        return try {
            process.waitFor()
        } catch (failure: InterruptedException) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw failure
        }
    }

    fun resourceMetrics(report: Path): List<BenchMetric> {
        val path = report.resolve("resources.txt")
        if (!Files.isRegularFile(path)) return emptyList()
        val values = Files.readAllLines(
            path,
        ).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return listOf("peakRssKiB" to "KiB", "userSeconds" to "s", "systemSeconds" to "s", "wallSeconds" to "s")
            .mapNotNull { (name, unit) ->
                values[name]?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let {
                    BenchMetric(name, it, unit, MetricDirection.LOWER)
                }
            }
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
