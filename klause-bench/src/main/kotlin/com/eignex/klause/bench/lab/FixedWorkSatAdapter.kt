package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.profile.FixedWorkRequest
import com.eignex.klause.bench.profile.FixedWorkSatMeasurement
import com.eignex.klause.bench.profile.FixedWorkUnit
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchCli
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchMetric
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchResources
import com.eignex.lab.bench.BenchResult
import com.eignex.lab.bench.BenchStatus
import com.eignex.lab.bench.BenchSuite
import com.eignex.lab.bench.MetricDirection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Fixed-work native SAT trajectory measurements for hash-pinned DIMACS sources. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(FixedWorkSatSuite, args, stdout = protocolOutput)
}

internal object FixedWorkSatSuite : BenchSuite {
    override fun describe() = BenchDescriptor("fixed-work-sat", "Fixed-work native SAT trajectory measurements")

    /** One declared corpus source. */
    internal data class Source(val path: String, val sha256: String) {
        init {
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "sha256 must be 64 lowercase hex digits" }
            require(path.isNotBlank() && !path.startsWith("/") && ".." !in path.split('/')) { "Invalid path: $path" }
        }
    }

    /** Validated request parameters: `sources` and `seeds` when [planned] is false, one of each in a case. */
    internal data class Options(
        val sources: List<Source>,
        val seeds: List<Long>,
        val unit: FixedWorkUnit,
        val limit: Long,
        val deadlineSeconds: Long,
        val measurement: String,
        val warmups: Int,
        val recipe: String,
        val traceRows: Int,
        val heapMiB: Int,
    ) {
        val processSeconds: Long get() = (warmups + 1L) * deadlineSeconds + PROCESS_MARGIN_SECONDS

        companion object {
            fun parse(parameters: Map<String, String>, planned: Boolean): Options {
                val selection = if (planned) setOf("source-path", "sha256", "seed") else setOf("sources", "seeds")
                val unknown = parameters.keys - REQUIRED - selection - OPTIONAL
                require(unknown.isEmpty() && parameters.keys.containsAll(selection)) {
                    "Parameters must be $REQUIRED and $selection, optionally $OPTIONAL"
                }
                val sources = if (planned) {
                    listOf(Source(parameters.getValue("source-path"), parameters.getValue("sha256")))
                } else parameters.getValue("sources").split(',').map { entry ->
                    Source(entry.substringAfter(':'), entry.substringBefore(':'))
                }
                require(sources.size in 1..MAX_SOURCES && sources.map { it.path }.distinct().size == sources.size) {
                    "1 to $MAX_SOURCES distinct sources"
                }
                val seeds = parameters.getValue(if (planned) "seed" else "seeds").split(',').map(String::toLong)
                require(seeds.size in 1..MAX_SEEDS && seeds.distinct().size == seeds.size) { "1 to $MAX_SEEDS seeds" }
                val limit = parameters.getValue("limit").toLong()
                require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT" }
                val deadline = parameters.getValue("deadline-seconds").toLong()
                require(deadline in 1..MAX_DEADLINE_SECONDS) { "deadline-seconds must be in 1..$MAX_DEADLINE_SECONDS" }
                val measurement = parameters["measurement"] ?: "cold"
                require(measurement in setOf("cold", "warm")) { "measurement must be cold or warm" }
                val warmups = parameters["warmups"]?.toInt() ?: if (measurement == "warm") 1 else 0
                require(if (measurement == "cold") warmups == 0 else warmups in 1..MAX_WARMUPS) {
                    "cold takes no warmups; warm takes 1..$MAX_WARMUPS"
                }
                val recipe = parameters["recipe"] ?: "clausal-balanced"
                BacktrackCatalog.byLabel(recipe)
                val rows = parameters["trace-rows"]?.toInt() ?: DEFAULT_TRACE_ROWS
                require(rows in 0..MAX_TRACE_ROWS) { "trace-rows must be in 0..$MAX_TRACE_ROWS" }
                val heap = parameters["heap-mib"]?.toInt() ?: DEFAULT_HEAP_MIB
                require(heap in MIN_HEAP_MIB..MAX_HEAP_MIB) { "heap-mib must be in $MIN_HEAP_MIB..$MAX_HEAP_MIB" }
                return Options(sources, seeds, FixedWorkUnit.of(parameters.getValue("unit")), limit, deadline,
                    measurement, warmups, recipe, rows, heap)
            }
        }
    }

    override fun plan(request: BenchRequest): List<BenchCase> {
        val options = Options.parse(request.parameters, planned = false)
        return options.sources.flatMap { source -> options.seeds.map { seed ->
            val key = "${options.measurement}/${options.unit.label}-${options.limit}/${source.path}/seed-$seed"
            BenchCase("fixed-work-sat/$key",
                request.parameters - "sources" - "seeds" +
                    mapOf("source-path" to source.path, "sha256" to source.sha256, "seed" to "$seed"),
                BenchResources(cpus = 1, memoryMiB = options.heapMiB + NATIVE_HEADROOM_MIB,
                    timeoutSeconds = options.processSeconds + CASE_MARGIN_SECONDS),
                inputs = listOf(BenchInput(source.path, BenchInputRoot.CORPUS, "model", source.sha256)),
                attributes = mapOf("suite" to "fixed-work-sat", "problem" to source.path,
                    "comparisonKey" to key, "pairKey" to key, "seed" to "$seed",
                    "recordSchema" to FixedWorkSatMeasurement.SCHEMA))
        } }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val options = Options.parse(case.parameters, planned = true)
        val declared = options.sources.single()
        val seed = options.seeds.single()
        val out = context.outputDirectory
        Files.createDirectories(out)
        val input = case.inputs.single()
        require(input.root == BenchInputRoot.CORPUS && input.sha256 == declared.sha256 &&
            input.path == declared.path) { "Case input differs from its parameters" }
        LabInputs.verify(case, context)
        val bytes = CorpusFiles.open(LabInputs.path(input, context).toFile()).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
        require(bytes.size <= MAX_SOURCE_BYTES) { "Source exceeds $MAX_SOURCE_BYTES bytes" }
        val source = out.resolve("source.cnf")
        Files.write(source, bytes)
        val request = FixedWorkRequest(source.toString(), sha256(bytes), seed, options.recipe, options.unit,
            options.limit, options.deadlineSeconds * MILLIS, options.warmups, options.traceRows)
        val requestFile = out.resolve("request.json")
        Files.writeString(requestFile, Reports.json.encodeToString(FixedWorkRequest.serializer(), request) + "\n")
        val identity = identity(context, input, bytes)
        Files.writeString(out.resolve(IDENTITY), identity.toString() + "\n")
        val argv = command(options, requestFile, out)
        val (exit, killed) = launch(argv, context, options.processSeconds)
        val invocations = out.resolve(FixedWorkSatMeasurement.INVOCATIONS).toFile()
        val records = if (invocations.isFile) {
            invocations.readLines().filter(String::isNotBlank).map { Reports.json.parseToJsonElement(it).jsonObject }
        } else emptyList()
        val runtime = out.resolve(FixedWorkSatMeasurement.RUNTIME).toFile()
            .takeIf(File::isFile)?.let { Reports.json.parseToJsonElement(it.readText()).jsonObject }
        val artifacts = listOf("request.json", IDENTITY, FixedWorkSatMeasurement.RUNTIME,
            FixedWorkSatMeasurement.INVOCATIONS, FixedWorkSatMeasurement.TRACE, LOG)
            .filter { Files.isRegularFile(out.resolve(it)) }.map { context.artifact(it) }
        return outcome(case.id, options, identity, runtime, records, exit, killed).let {
            BenchResult(it.caseId, it.status, it.metrics, artifacts, it.details, it.message)
        }
    }

    /** The case result for the child's [records], process [exit] code and emergency [killed] flag. */
    internal fun outcome(
        caseId: String,
        options: Options,
        identity: JsonObject,
        runtime: JsonObject?,
        records: List<JsonObject>,
        exit: Int?,
        killed: Boolean,
    ): BenchResult {
        val measured = records.lastOrNull()?.takeIf { it.text("role") == "measured" }
        val warmups = records.filter { it.text("role") == "warmup" }
        val comparable = measured?.flag("comparable") == true
        val interrupted = measured == null || measured.flag("interrupted")
        val truncated = measured?.get("trajectory")?.jsonObject?.flag("rowsTruncated") == true
        val details = buildJsonObject {
            put("schema", FixedWorkSatMeasurement.SCHEMA)
            put("measurement", options.measurement)
            put("unit", options.unit.label)
            put("limit", options.limit)
            put("deadlineSeconds", options.deadlineSeconds)
            put("recipe", options.recipe)
            put("identity", identity)
            put("runtime", runtime ?: JsonNull)
            put("process", buildJsonObject {
                put("exit", exit)
                put("emergencyKilled", killed)
                put("ceilingSeconds", options.processSeconds)
            })
            put("comparable", comparable)
            put("interrupted", interrupted)
            put("traceRowsTruncated", truncated)
            put("warmupDigestsMatch", if (measured == null || warmups.isEmpty()) JsonNull else JsonPrimitive(
                warmups.all { it.digest() == measured.digest() }))
            put("warmups", JsonArray(warmups))
            put("measured", measured ?: JsonNull)
            put("performanceQualified", false)
        }
        if (measured == null) {
            val reason = if (killed) "killed at the ${options.processSeconds}s emergency process ceiling"
            else "exited with ${exit ?: "no status"}"
            return BenchResult(caseId, BenchStatus.FAILED, details = details,
                message = "Measured invocation incomplete: child $reason; ${warmups.size} warmups retained")
        }
        val counters = measured.getValue("counters").jsonObject
        val metrics = buildList {
            add(BenchMetric("completedWork", counters.number("work").toDouble(), "work", MetricDirection.NONE))
            add(BenchMetric("decisions", counters.number("decisions").toDouble(), "decisions", MetricDirection.NONE))
            add(BenchMetric("failures", counters.number("failures").toDouble(), "failures", MetricDirection.NONE))
            if (comparable) {
                val interval = measured.getValue("measured").jsonObject
                val limit = measured.getValue("limitPoint").jsonObject
                time("measuredElapsedMs", interval.long("elapsedNs"))
                time("measuredThreadCpuMs", interval.long("threadCpuNs"))
                time("measuredProcessCpuMs", interval.long("processCpuNs"))
                time("limitElapsedMs", limit.long("elapsedNs"))
                time("limitThreadCpuMs", limit.long("threadCpuNs"))
                time("preparationElapsedMs", measured.getValue("preparation").jsonObject.long("elapsedNs"))
            }
        }
        val message = when {
            !comparable -> "Interrupted by the ${options.deadlineSeconds}s emergency deadline before the fixed-work " +
                "limit; partial work is not comparable"
            truncated -> "Retained trace rows are truncated; the digest still covers every event"
            else -> null
        }
        return BenchResult(caseId, BenchStatus.SUCCESS, metrics, details = details, message = message)
    }

    private fun MutableList<BenchMetric>.time(name: String, nanos: Long?) {
        if (nanos != null) add(BenchMetric(name, nanos / NANOS_PER_MILLI, "ms", MetricDirection.LOWER))
    }

    private fun command(options: Options, request: Path, out: Path): List<String> = buildList {
        add(Path.of(System.getProperty("java.home"), "bin", "java").toString())
        add("-Xms${options.heapMiB}m")
        add("-Xmx${options.heapMiB}m")
        add("-XX:ActiveProcessorCount=1")
        add("-XX:+UseSerialGC")
        add("--enable-native-access=ALL-UNNAMED")
        add("--add-modules=jdk.incubator.vector")
        add("-cp")
        add(System.getProperty("java.class.path"))
        add("com.eignex.klause.bench.profile.FixedWorkSatMeasurementKt")
        add(request.toString())
        add(out.toString())
    }

    /** Run the child to completion or the emergency [ceilingSeconds]; returns its exit code and whether it was killed. */
    private fun launch(argv: List<String>, context: BenchContext, ceilingSeconds: Long): Pair<Int?, Boolean> {
        val process = ProcessBuilder(argv).directory(context.outputDirectory.toFile()).redirectErrorStream(true)
            .redirectOutput(context.outputDirectory.resolve(LOG).toFile()).apply {
                environment().keys.removeAll { it in setOf("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS",
                    "JDK_JAVA_OPTIONS") }
            }.start()
        fun kill() {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)
        }
        return try {
            if (process.waitFor(ceilingSeconds, TimeUnit.SECONDS)) process.exitValue() to false
            else {
                kill()
                null to true
            }
        } catch (failure: InterruptedException) {
            kill()
            Thread.currentThread().interrupt()
            throw failure
        }
    }

    private fun identity(context: BenchContext, input: BenchInput, bytes: ByteArray): JsonObject {
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
        val engine = classpath.filter { it.isFile && it.name.startsWith("klause-jvm-") && it.name.endsWith(".jar") }
            .associate { it.name to sha256(it.readBytes()) }
        require(engine.isNotEmpty()) { "Installed solver library unavailable on the classpath" }
        val comparison = ComparisonProvenance.current
        return buildJsonObject {
            put("revision", revision(context))
            put("engine", JsonObject(engine.mapValues { JsonPrimitive(it.value) }))
            put("harnessFingerprint", comparison.harnessFingerprint)
            put("libraryFingerprint", comparison.libraryFingerprint)
            put("sourcePath", input.path)
            put("declaredSha256", input.sha256)
            put("plainSha256", sha256(bytes))
            put("plainBytes", bytes.size)
        }
    }

    private fun revision(context: BenchContext): String? = runCatching {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(context.worktree?.toFile())
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        text.takeIf { process.waitFor() == 0 && it.matches(Regex("[0-9a-f]{40}")) }
    }.getOrNull()

    private fun JsonObject.digest(): String? = get("trajectory")?.jsonObject?.get("prefix")
        ?.takeIf { it !is JsonNull }?.jsonObject?.text("digest")

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.long(key: String): Long? = get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull

    private fun JsonObject.number(key: String): Long = requireNotNull(long(key)) { "Missing $key" }

    private val REQUIRED = setOf("unit", "limit", "deadline-seconds")
    private val OPTIONAL = setOf("measurement", "warmups", "recipe", "trace-rows", "heap-mib")
    private const val IDENTITY = "identity.json"
    private const val LOG = "measure.log"
    private const val MAX_SEEDS = 8
    private const val MAX_SOURCES = 8
    private const val MAX_LIMIT = 1_000_000_000L
    private const val MAX_DEADLINE_SECONDS = 900L
    private const val MAX_WARMUPS = 3
    private const val DEFAULT_TRACE_ROWS = 65_536
    private const val MAX_TRACE_ROWS = 1_048_576
    private const val DEFAULT_HEAP_MIB = 2048
    private const val MIN_HEAP_MIB = 256
    private const val MAX_HEAP_MIB = 3072
    private const val NATIVE_HEADROOM_MIB = 1024L
    private const val PROCESS_MARGIN_SECONDS = 60L
    private const val CASE_MARGIN_SECONDS = 60L
    private const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
    private const val MILLIS = 1000L
    private const val KILL_GRACE_SECONDS = 10L
    private const val NANOS_PER_MILLI = 1_000_000.0
}
