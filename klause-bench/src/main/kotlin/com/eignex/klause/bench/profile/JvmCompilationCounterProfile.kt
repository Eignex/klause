package com.eignex.klause.bench.profile

import com.eignex.klause.bench.report.Reports
import java.io.File
import java.lang.management.CompilationMXBean
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Compilation-counter observations and read costs in fresh JVMs without flight recording. */
object JvmCompilationCounterProfile {
    private const val CHILD = "KLAUSE_COMPILATION_COUNTER_CHILD"
    private const val SAMPLES = 3
    private const val READS = 100_000
    private const val BATCHES = 7

    /** Run one catalog solve per child and write the counter observations into the lab output directory. */
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val solveArgs = args.toList().let { values ->
            if (values.any { it.startsWith("timeout=") }) values else values + "timeout=10000"
        } + "repeats=1"
        if (System.getenv(CHILD) == "1") {
            measure(solveArgs, out)
            return
        }
        val samples = buildJsonArray {
            repeat(SAMPLES) { index ->
                val directory = out.resolve("sample-$index").apply { mkdirs() }
                launch(solveArgs, directory)
                add(Reports.json.parseToJsonElement(directory.resolve("counter.json").readText()))
            }
        }
        val report = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("samples", samples)
        }
        out.resolve("compilation-counter.json").writeText(report.toString() + "\n")
        println(report)
    }

    private fun launch(args: List<String>, out: File) {
        val command = arrayListOf(
            File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx1g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector",
        )
        for (name in listOf("klause.workspace.root", "klause.bench.corpusCache")) {
            System.getProperty(name)?.let { command += "-D$name=$it" }
        }
        command += listOf("-cp", System.getProperty("java.class.path"), javaClass.name)
        command += args
        val builder = ProcessBuilder(command).redirectOutput(out.resolve("stdout.txt"))
            .redirectError(out.resolve("stderr.txt"))
        builder.environment().apply {
            listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach(::remove)
            put(CHILD, "1")
            put("OUT", out.absolutePath)
        }
        val child = builder.start()
        try {
            check(child.waitFor() == 0) { "counter child failed; inspect ${out.resolve("stderr.txt")}" }
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
        }
    }

    private fun measure(args: List<String>, out: File) {
        val setupStart = System.nanoTime()
        val counter = ManagementFactory.getCompilationMXBean()?.takeIf { it.isCompilationTimeMonitoringSupported }
        val readStart = System.nanoTime()
        val initialMs = counter?.totalCompilationTime
        val firstReadNanos = System.nanoTime() - readStart
        val setupNanos = System.nanoTime() - setupStart
        val windowStart = System.nanoTime()
        val timings = SolveTiming.measure(args, out)
        val finalMs = counter?.totalCompilationTime
        val windowNanos = System.nanoTime() - windowStart
        val batches = counter?.let(::readCosts)
        val report = buildJsonObject {
            put("javaVersion", System.getProperty("java.version"))
            put("vmName", System.getProperty("java.vm.name"))
            put("vmArguments", buildJsonArray {
                ManagementFactory.getRuntimeMXBean().inputArguments.forEach { add(JsonPrimitive(it)) }
            })
            put("flightRecordingRunning", jdk.jfr.FlightRecorder.isInitialized() &&
                jdk.jfr.FlightRecorder.getFlightRecorder().recordings.any { it.state == jdk.jfr.RecordingState.RUNNING })
            put("supported", counter != null)
            put("counterSetupNanos", setupNanos)
            put("firstReadNanos", if (counter == null) JsonNull else JsonPrimitive(firstReadNanos))
            put("initialCompilerMs", initialMs?.let(::JsonPrimitive) ?: JsonNull)
            put("finalCompilerMs", finalMs?.let(::JsonPrimitive) ?: JsonNull)
            put("windowCompilerMs", if (initialMs == null || finalMs == null) JsonNull
                else JsonPrimitive(finalMs - initialMs))
            put("windowWallNanos", windowNanos)
            put("window", "catalog resolution, preparation, solve and SolveTiming report writing")
            put("solve", Reports.json.encodeToJsonElement(timings))
            put("readsPerBatch", READS)
            put("warmReadBatchesNanos", batches?.first ?: JsonNull)
            put("readChecksum", batches?.second?.let(::JsonPrimitive) ?: JsonNull)
        }
        out.resolve("counter.json").writeText(report.toString() + "\n")
    }

    private fun readCosts(counter: CompilationMXBean): Pair<JsonArray, Long> {
        var checksum = 0L
        repeat(10_000) { checksum += counter.totalCompilationTime }
        val batches = buildJsonArray {
            repeat(BATCHES) {
                val start = System.nanoTime()
                repeat(READS) { checksum += counter.totalCompilationTime }
                add(JsonPrimitive(System.nanoTime() - start))
            }
        }
        return batches to checksum
    }
}
