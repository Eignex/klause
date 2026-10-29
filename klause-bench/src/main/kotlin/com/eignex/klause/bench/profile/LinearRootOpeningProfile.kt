package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PlannedSearch
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.result.OpenTheoryWorkSink
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearRootOpeningProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT")))
        if (System.getenv("KLAUSE_LINEAR_ROOT_MEASURED_JVM") != "1") {
            launch(args, out)
            return
        }
        require(Runtime.getRuntime().availableProcessors() == 1)
        if (args.size == 1 && args[0].startsWith("batch64=")) {
            val requestBytes = Base64.getDecoder().decode(args[0].substringAfter('='))
            require(requestBytes.size <= 32 * 1024)
            val requests = Json.decodeFromString<List<List<String>>>(requestBytes.decodeToString())
            require(requests.size in 1..4)
            requests.forEachIndexed { index, request -> inspect(request.toTypedArray(), out.resolve("$index")) }
        } else inspect(args, out)
    }

    private fun inspect(args: Array<String>, out: File) {
        out.mkdirs()
        val options = args.associate { argument ->
            require('=' in argument) { "expected key=value" }
            argument.substringBefore('=') to argument.substringAfter('=')
        }
        val required = setOf("suite", "problem", "sha256")
        require(options.keys == required || options.keys == required + "inspect")
        val inspection = options["inspect"]
        val inspections = setOf("disjunction-names", "partial-arithmetic", "partial-implication",
            "partial-propagation", "partial-propagation-cache", "partial-propagation-cached",
            "partial-source-batching", "partial-source-batching-plain", "partial-row-activation")
        require(inspection == null || inspection in inspections)
        val expected = options.getValue("sha256")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val file = CorpusFetcher.resolve(reference.source)
        val bytes = CorpusFiles.open(file).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
        require(bytes.size <= MAX_SOURCE_BYTES)
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        require(actual == expected) { "original source identity differs" }
        out.resolve("source.smt2").writeBytes(bytes)
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val parsingStarted = System.nanoTime()
        val parsingStop = Cancellation.after(OPENING_BUDGET_MS.milliseconds)
        val admitted = SmtLib.parse(bytes.decodeToString(), cancellation = parsingStop)
        val compiled = admitted
        val parsingNs = System.nanoTime() - parsingStarted
        require(compiled.objective == null) { "only satisfaction openings are supported" }
        if (inspection != null && inspection.startsWith("partial-")) {
            LinearPrefixInspection.inspect(compiled, out, actual, probe = inspection == "partial-implication",
                integrated = inspection.startsWith("partial-propagation") || inspection == "partial-source-batching" ||
                    inspection == "partial-row-activation",
                cached = inspection == "partial-propagation-cache", reuse = inspection == "partial-propagation-cached",
                batching = inspection.startsWith("partial-source-batching"),
                incremental = inspection == "partial-row-activation")
            return
        }
        val deadlineStarted = System.nanoTime()
        val stop = Cancellation.after(OPENING_BUDGET_MS.milliseconds)
        val params = TheoryParams(timeout = stop, randomSeed = SEED)
        // Reflection keeps internal root inspection out of the published solver API.
        val factory = OpenTheoryPipeline.javaClass.declaredMethods.single {
            it.name.startsWith("engineFor") && it.parameterCount == 1
        }
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            compiled.source, ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK, cancellation = stop,
        ))
        val model = requireNotNull(invocation.preparedPlan?.model) { "source has no factor inspection view" }
        val request = (invocation.route as? SourceProblemRoute.OpenTheory)?.request
            ?: error("source setup did not select a linear opening")
        val engine = requireNotNull(factory.invoke(OpenTheoryPipeline, request))
        val stateType = Class.forName("com.eignex.klause.solver.pipeline.OpenTheorySolveState")
        val state = stateType.getDeclaredConstructor(TheoryParams::class.java).newInstance(params)
        val begin = engine.javaClass.declaredMethods.single { it.name.startsWith("begin") && it.parameterCount == 3 }
        val openingStarted = System.nanoTime()
        val started = requireNotNull(begin.invoke(engine, params, state, SearchNodePolicy.ExpandAll))
        val openingNs = System.nanoTime() - openingStarted
        val deadlineElapsedNs = System.nanoTime() - deadlineStarted
        val deadlinePassedAtReturn = stop.deadline()?.hasPassedNow() == true
        val cancelledAtReturn = stop()
        val run = member(started, "getRun")
        try {
            val decided = member(started, "getResult") as? OpenTheoryResult
            val planned = run?.let { field(it, "planned") as PlannedSearch }
            val session = planned?.session
            val work = requireNotNull(member(state, "getWork")) as OpenTheoryWorkSink
            val counters = work.snapshot()
            val result = buildJsonObject {
                put("revision", Reports.readGitSha())
                put("sourceSha256", actual)
                put("suite", options.getValue("suite"))
                put("problem", options.getValue("problem"))
                put("entryPoint", "direct-library-linear-root-opening")
                put("sourceAdmission", admitted.javaClass.simpleName)
                put("cliRoutingEquivalence", "unverified")
                put("sourceVerdictClaim", false)
                put("traversalAdvanced", false)
                put("processors", Runtime.getRuntime().availableProcessors())
                put("seed", SEED)
                put("openingBudgetMs", OPENING_BUDGET_MS)
                put("parsingBeforeDeadlineNs", parsingNs)
                put("deadlineElapsedAtReturnNs", deadlineElapsedNs)
                put("openingNs", openingNs)
                put("deadlinePassedAtReturn", deadlinePassedAtReturn)
                put("cancelledAtReturn", cancelledAtReturn)
                put("opening", started.javaClass.simpleName)
                put("rawDecidedResult", decided?.javaClass?.simpleName)
                put("rawDecidedReason", (decided as? OpenTheoryResult.Unknown)?.reason?.name)
                put("rawSearchStopCause", session?.executionControls?.stopCause?.name)
                put("rawSessionCheckAttempts", session?.let { field(it, "checks") as Long })
                put("acceptedTheoryChecks", counters.openTheoryChecks)
                put("committedBooleanDecisions", counters.openBoolDecisions)
                put("committedIntegerDecisions", counters.openIntDecisions)
                put("committedTheoryDecisions", counters.openTheoryDecisions)
                put("chargedWork", counters.openWork)
                put("workAllowanceExhausted", work.exhausted)
                put("decisionLevel", session?.decisionLevel)
                put("finiteFixpointCancelled", planned?.cp?.session?.fixpointCancelled)
                put("theoryComponent", planned?.theory?.javaClass?.simpleName)
                put("compiledBooleans", model.numBoolVars)
                put("compiledIntegers", model.numIntVars)
                put("compiledReals", model.numRealVars)
                put("compiledFactors", model.factors.size)
                put("sourceBooleanNames", compiled.boolVarNames.size)
                put("sourceIntegerNames", compiled.intVarNames.size)
                put("sourceRealNames", compiled.realVarNames.size)
                put("javaVersion", System.getProperty("java.version"))
                put("runtimeOptions", buildJsonArray {
                    ManagementFactory.getRuntimeMXBean().inputArguments.forEach { add(JsonPrimitive(it)) }
                })
            }
            out.resolve("root-opening.json").writeText(result.toString() + "\n")
            decided?.let { out.resolve("decided-stats.txt").writeText(it.stats.toString() + "\n") }
            println(result)
        } finally {
            (run as? AutoCloseable)?.close()
        }
        if (inspection != null) LinearDisjunctionInspection.inspect(engine, stop, out)
    }

    private fun launch(args: Array<String>, out: File) {
        val command = arrayListOf(
            File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector",
            "-XX:StartFlightRecording=filename=${out.resolve("measured-profile.jfr")}," +
                "settings=profile,dumponexit=true",
        )
        for (name in listOf("klause.workspace.root", "klause.bench.corpusCache")) {
            System.getProperty(name)?.let { command += "-D$name=$it" }
        }
        command += listOf("-cp", System.getProperty("java.class.path"), javaClass.name)
        command += args
        out.resolve("launcher.json").writeText(buildJsonObject {
            put("command", buildJsonArray { command.forEach { add(JsonPrimitive(it)) } })
        }.toString() + "\n")
        val builder = ProcessBuilder(command)
            .redirectOutput(out.resolve("measured.stdout"))
            .redirectError(out.resolve("measured.stderr"))
        builder.environment()["KLAUSE_LINEAR_ROOT_MEASURED_JVM"] = "1"
        val process = builder.start()
        var transportTimeout = false
        try {
            transportTimeout = !process.waitFor(PROCESS_LIMIT_SECONDS, TimeUnit.SECONDS)
        } finally {
            val forced = process.isAlive
            if (forced) {
                process.destroyForcibly()
                process.waitFor()
            }
            out.resolve("measured.exit").writeText("${process.exitValue()}\n")
            out.resolve("transport-status.json").writeText(buildJsonObject {
                put("timeout", transportTimeout)
                put("forcedTermination", forced)
                put("exit", process.exitValue())
            }.toString() + "\n")
        }
        check(!transportTimeout) { "measured root JVM transport timed out" }
        check(process.exitValue() == 0) { "measured root JVM failed" }
    }

    private fun member(owner: Any, name: String): Any? = owner.javaClass.methods
        .singleOrNull { it.name == name && it.parameterCount == 0 }?.invoke(owner)

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private const val OPENING_BUDGET_MS = 10_000L
    private const val SEED = 1L
    private const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
    private const val PROCESS_LIMIT_SECONDS = 120L
}
