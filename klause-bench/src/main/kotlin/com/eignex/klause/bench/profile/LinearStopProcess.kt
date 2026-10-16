package com.eignex.klause.bench.profile

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearStopProcess {
    fun launch(args: Array<String>, out: File) {
        val command = arrayListOf(File(System.getProperty("java.home"), "bin/java").path)
        command += System.getenv("KLAUSE_CLI_OPTS").orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }
        command += listOf("-XX:ActiveProcessorCount=1", "--enable-native-access=ALL-UNNAMED",
            "--add-modules=jdk.incubator.vector")
        for (name in listOf("klause.workspace.root", "klause.bench.corpusCache")) {
            System.getProperty(name)?.let { command += "-D$name=$it" }
        }
        command += listOf("-cp", System.getProperty("java.class.path"), LinearStopAttributionProfile::class.java.name)
        command += args
        out.resolve("launcher.json").writeText(buildJsonObject {
            put("command", buildJsonArray { command.forEach { add(JsonPrimitive(it)) } })
        }.toString() + "\n")
        val builder = ProcessBuilder(command).redirectOutput(out.resolve("measured.stdout"))
            .redirectError(out.resolve("measured.stderr"))
        builder.environment()["KLAUSE_LINEAR_STOP_CHILD"] = "1"
        val process = builder.start()
        val completed = process.waitFor(30, TimeUnit.MINUTES)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor()
        }
        out.resolve("transport.json").writeText(buildJsonObject {
            put("exit", process.exitValue())
            put("watchdog", !completed)
        }.toString() + "\n")
        check(completed && process.exitValue() == 0) { "linear stop diagnostic child failed" }
    }
}
