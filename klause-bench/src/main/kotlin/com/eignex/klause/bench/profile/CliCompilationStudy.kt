package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal object CliCompilationStudy {
    private const val MAX_METADATA_BYTES = 1024 * 1024
    private const val MAX_FAMILY_BYTES = 16 * 1024 * 1024
    private const val MAX_SOURCE_BYTES = 128L * 1024 * 1024
    private const val MAX_ALL_SOURCE_BYTES = 1024L * 1024 * 1024
    private const val GRACE_MILLIS = 120_000L
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) = ProfileCaptureArchive.run {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        val required = setOf("selection", "sha256", "control", "candidate", "plan", "plan-sha256")
        val withFamilies = required + setOf("families", "families-sha256")
        require(args.size == options.size && options.keys in listOf(required, withFamilies))
        CompilationStudyBuilds.validateHeads(listOf("control", "candidate").associateWith { options.getValue(it) })
        val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
        val root = File(requireNotNull(System.getProperty("klause.workspace.root"))).canonicalFile
        require(listOf(out, root).all { file -> file.path.none { it.isWhitespace() } })
        val selected = download(options.getValue("selection"), options.getValue("sha256"), MAX_METADATA_BYTES)
        val planned = download(options.getValue("plan"), options.getValue("plan-sha256"), MAX_METADATA_BYTES)
        File(out, "selection.json").writeBytes(selected)
        File(out, "study-plan.json").writeBytes(planned)
        val inputs = json.decodeFromString<List<CompilationStudyInput>>(selected.decodeToString())
        val plans = json.decodeFromString<List<CompilationStudyPlan>>(planned.decodeToString())
        CompilationStudyPlan.validate(inputs, plans)
        options["families"]?.let { address ->
            File(out, "compilation-families.json").writeBytes(
                download(address, options.getValue("families-sha256"), MAX_FAMILY_BYTES))
        }
        Reports.writeJson(File(out, "requested-options.json").path, options)
        InstalledBuild.writeManifest(File(out, "build-provenance.json"))
        val cpu = File("/proc/self/status").readLines().single { it.startsWith("Cpus_allowed_list:") }
            .substringAfter(':').trim().substringBefore(',').substringBefore('-').toInt()
        File(out, "processor-affinity.txt").writeText("$cpu\n")
        prepareInputs(inputs, out)
        configure(root, out)
        val measurements = mutableListOf<CompilationStudyMeasurement>()
        var allAttempted = false
        var checksCompleted = false
        try {
            CompilationStudyBuilds(root, out).use { workspace ->
                val builds = workspace.prepare(listOf("control", "candidate").associateWith { options.getValue(it) })
                for (plan in plans) for (index in plan.inputs) for (repetition in 0 until plan.repeats) {
                    for (engine in plan.engines) for (arm in plan.armOrder(index, repetition)) {
                        val measurement = measure(out, builds.getValue(arm), cpu, inputs[index], index,
                            repetition, engine, plan)
                        measurements += measurement
                        Reports.writeJson(File(out, "cases.json").path, measurements)
                    }
                }
                allAttempted = true
                // Checker setup and source solving stay outside every measured child invocation.
                CompilationStudyChecks.run(root, out, measurements)
                checksCompleted = true
            }
        } finally {
            Reports.writeJson(File(out, "completion.json").path, CompilationStudyCompletion(
                allAttempted, checksCompleted, measurements.size,
                measurements.isNotEmpty() && measurements.all {
                    it.exit == 0 && it.sourceHashesMatch && it.error == null && !it.interrupted &&
                        it.captureStatus.recordedCliSucceeded &&
                        it.captureStatus.requestedRecordingFilesNonempty
                }))
        }
    }

    private fun download(address: String, expected: String, maximum: Int): ByteArray {
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val uri = URI(address)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null)
        val connection = uri.toURL().openConnection().apply { connectTimeout = 20_000; readTimeout = 20_000 }
        val bytes = connection.getInputStream().use { it.readNBytes(maximum + 1) }
        require(bytes.size <= maximum && sha256(bytes) == expected) { "Study metadata identity differs" }
        return bytes
    }

    private fun prepareInputs(inputs: List<CompilationStudyInput>, out: File) {
        val sources = File(out, "source-root").apply { mkdirs() }
        var total = 0L
        for (input in inputs) {
            val ref = Catalog.uncapped(input.suite).problems.single { it.name == input.problem }
            require(ref.format.name == input.format)
            val files = listOfNotNull("model" to ref.source, ref.data?.let { "data" to it })
            require(files.map { it.first }.toSet() == input.sourceHashes.keys)
            for ((label, source) in files) {
                val target = File(sources, "${input.sourceHashes.getValue(label)}.txt")
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                CorpusFiles.open(CorpusFetcher.resolve(source)).use { stream ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            size += count
                            total += count
                            require(size <= MAX_SOURCE_BYTES && total <= MAX_ALL_SOURCE_BYTES)
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
                require(actual == input.sourceHashes.getValue(label)) { "Original study source bytes differ" }
            }
        }
    }

    private fun configure(root: File, out: File) {
        val jfr = File(System.getProperty("java.home"), "bin/jfr").path
        CompilationStudyBuilds.command(root, out, "jfc", listOf(jfr, "configure", "--input", "profile",
            "--output", File(out, "compilation.jfc").path,
            "jdk.Compilation#enabled=true", "jdk.Compilation#threshold=0ms",
            "jdk.CompilerPhase#enabled=true", "jdk.CompilerPhase#threshold=0ms", "jdk.CompilerInlining#enabled=true",
            "jdk.InitialEnvironmentVariable#enabled=false", "jdk.InitialSystemProperty#enabled=false"))
        CompilationStudyBuilds.command(root, out, "java-flags", listOf(
            File(System.getProperty("java.home"), "bin/java").path, "-XX:+PrintFlagsFinal", "-version"))
    }

    @Suppress("LongMethod", "LongParameterList", "TooGenericExceptionCaught")
    private fun measure(
        out: File,
        build: CompilationStudyBuild,
        cpu: Int,
        problem: CompilationStudyInput,
        index: Int,
        repetition: Int,
        engine: String,
        plan: CompilationStudyPlan,
    ): CompilationStudyMeasurement {
        val name = plan.caseName(index, repetition, engine, build.arm)
        val directory = File(out, "cases/$name").apply { check(mkdirs()) }
        val recording = File(directory, "cli.jfr")
        val command = listOf("/usr/bin/time", "-v", "-o", File(directory, "resources.txt").path,
            "taskset", "-c", cpu.toString(), build.bench.path, "solve-one", "suite=${problem.suite}",
            "problem=${problem.problem}", "engine=$engine", "processors=1", "timeout=${plan.timeout}",
            "solver-seed=${plan.solverSeed}", "exact=${plan.exact}", "out=${directory.path}") +
            plan.params.map { "param=$it" } + listOfNotNull(plan.lp?.let { "lp=$it" }) +
            if (problem.format in setOf("MINIZINC", "XCSP3")) listOf("param=source-validation=true") else emptyList()
        val builder = ProcessBuilder(command).directory(build.root)
            .redirectOutput(File(directory, "bench.stdout")).redirectError(File(directory, "bench.stderr"))
        val runtime = File(System.getProperty("java.home"))
        val profileOptions = if (plan.profile) {
            " -XX:StartFlightRecording=filename=${recording.path}," +
                "settings=${out.path}/compilation.jfc,dumponexit=true" +
                " -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=${directory.path}/hotspot.xml" +
                " -Xlog:class+load=info:file=${directory.path}/class-load.log:time,level,tags"
        } else ""
        val environment = builder.environment().apply {
            for (key in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) remove(key)
            put("JAVA_HOME", runtime.path)
            put("JAVA_OPTS", "-Xmx1g --enable-preview --add-modules=jdk.incubator.vector " +
                "--enable-native-access=ALL-UNNAMED -Dklause.bench.cache=false " +
                "-Dklause.bench.corpusCache=${System.getenv("KLAUSE_CORPUS")} " +
                "-Dklause.workspace.root=${build.root.path}")
            put("KLAUSE_CLI_PREFLIGHT_OPTS", "-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1")
            put("KLAUSE_CLI_OPTS", getValue("KLAUSE_CLI_PREFLIGHT_OPTS") + profileOptions)
            put("KLAUSE_BENCH_PROVENANCE", build.provenance.path)
            put("KLAUSE_BENCH_CORPUS_MAX_GB", "off")
            for (key in listOf("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS")) put(key, "1")
        }
        val launcher = File(build.root, "klause-cli/build/install/klause-cli-jvm/bin/klause-cli")
        val harnessManifest = File(out, "${build.arm}-harness-provenance.json")
        InstalledBuild.readManifest(build.provenance, launcher, runtime, environment)
        InstalledBuild.readManifest(harnessManifest, build.bench, runtime, environment)
        Reports.writeJson(File(directory, "command.json").path, command)
        val started = Instant.now().toString()
        var process: Process? = null
        var interrupted = false
        var failure: String? = null
        try {
            process = builder.start()
            if (!process.waitFor(plan.timeout * 2 + GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                interrupted = true
                CompilationStudyBuilds.kill(process)
            }
        } catch (error: Exception) {
            failure = error.toString()
        } finally {
            process?.takeIf { it.isAlive }?.let(CompilationStudyBuilds::kill)
        }
        val ended = Instant.now().toString()
        val record = directory.listFiles().orEmpty().filter { it.extension == "json" && it.name != "command.json" }
            .mapNotNull { file -> runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() }
            .singleOrNull { "sourceHashes" in it }
        if (record == null && failure == null) failure = "Missing or ambiguous original benchmark record"
        InstalledBuild.readManifest(build.provenance, launcher, runtime, environment)
        InstalledBuild.readManifest(harnessManifest, build.bench, runtime, environment)
        val hashesMatch = record?.get("sourceHashes") == json.encodeToJsonElement(problem.sourceHashes)
        val measurement = CompilationStudyMeasurement(name, index, repetition, engine, problem, command,
            started, ended, process?.exitValue(), hashesMatch,
            record, plan.mode, build.arm, plan.recipe, plan.profile, environment.getValue("KLAUSE_CLI_OPTS"),
            environment.getValue("JAVA_OPTS"), environment.getValue("KLAUSE_CLI_PREFLIGHT_OPTS"),
            interrupted, failure, recording.isFile,
            File(directory, "hotspot.xml").isFile,
            listOf("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS").associateWith(environment::getValue),
            CompilationStudyCaptureStatus.inspect(record, plan.profile, recording.length(),
                File(directory, "hotspot.xml").length()))
        Reports.writeJson(File(directory, "measurement.json").path, measurement)
        return measurement
    }
}

@Serializable
internal data class CompilationStudyMeasurement(
    val case: String, val index: Int, val repetition: Int, val engine: String, val problem: CompilationStudyInput,
    val command: List<String>, val started: String, val ended: String, val exit: Int?, val sourceHashesMatch: Boolean,
    val record: JsonElement?, val mode: String, val arm: String, val recipe: String?, val profile: Boolean,
    val requestedCliOpts: String, val requestedBenchOpts: String, val requestedPreflightCliOpts: String,
    val interrupted: Boolean, val error: String?,
    val recordingPresent: Boolean, val compilationLogPresent: Boolean,
    val requestedThreadLimits: Map<String, String>,
    val captureStatus: CompilationStudyCaptureStatus,
)

@Serializable
private data class CompilationStudyCompletion(
    val allPlannedInvocationsAttempted: Boolean,
    val sourceCheckersCompleted: Boolean,
    val capturedCases: Int,
    val allInvocationCapturesSuccessful: Boolean,
    val recordingContentsVerified: Boolean = false,
    val sourceOutcomesValidated: Boolean = false,
    val acceptedSavingsCredit: Boolean = false,
)
