package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.result.OpenTheoryWorkSink
import com.eignex.klause.solver.result.SmtStats
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.util.Cancellation
import java.io.File
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object LinearQueryEligibilityProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (System.getenv("KLAUSE_QUERY_ELIGIBILITY_CHILD") != "1") {
            launch(args, out)
            return
        }
        System.setOut(PrintStream(output.wrap(System.out), true, Charsets.UTF_8))
        System.setErr(PrintStream(output.wrap(System.err), true, Charsets.UTF_8))
        require(Runtime.getRuntime().availableProcessors() == 1)
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == 3 && options.keys == setOf("suite", "problem", "sha256"))
        require(options.getValue("sha256").matches(Regex("[0-9a-f]{64}")))
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val source = CorpusFetcher.resolve(reference.source)
        val bytes = CorpusFiles.open(source).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
        require(bytes.size <= MAX_SOURCE_BYTES && sha256(bytes) == options.getValue("sha256"))
        writeBounded(out.resolve("build-provenance.json"), Reports.json.encodeToString(InstalledBuild.reference))
        verifyLoadedSolver(out)
        val samples = LinearQueryInputSamples<Any>()
        var probe: LinearQueryInputProbe? = null
        var observing = true
        var unboundPolls = 0L
        var probeStatus = "not-attached"
        var component: Any? = null
        var run: Any? = null
        var state: Any? = null
        var resultClass = "deadline-before-engine"
        var sourceCounts: JsonObject? = null
        val started = System.nanoTime()
        val original = Cancellation.after(BUDGET_MS.milliseconds)
        val stop = LinearQueryObservationCancellation(original) {
            if (observing) {
                val current = probe
                if (current == null) {
                    if (unboundPolls < Long.MAX_VALUE) unboundPolls++
                } else current.observe()
            }
        }
        var timedNs: Long
        try {
            val admitted = SmtLibAdmission.parse(bytes.decodeToString(), cancellation = stop)
            val compiled = requireNotNull((admitted as? SmtLibInput.Linear)?.problem) { "not a linear admission" }
            require(compiled.objective == null) { "satisfaction input required" }
            sourceCounts = buildJsonObject {
                put("booleans", compiled.model.numBoolVars)
                put("integers", compiled.model.numIntVars)
                put("reals", compiled.model.numRealVars)
                put("factors", compiled.model.factors.size)
            }
            if (!original()) {
                val factory = method(OpenTheoryPipeline, "engineFor", 1)
                val engine = requireNotNull(factory.invoke(OpenTheoryPipeline, OpenTheoryRequest(compiled.model)))
                val params = TheoryParams(timeout = stop, randomSeed = 1L)
                val stateType = Class.forName("com.eignex.klause.solver.pipeline.OpenTheorySolveState")
                state = stateType.getDeclaredConstructor(TheoryParams::class.java).newInstance(params)
                val opening = requireNotNull(method(engine, "begin", 3)
                    .invoke(engine, params, state, SearchNodePolicy.ExpandAll))
                run = member(opening, "getRun")
                var result = member(opening, "getResult")
                if (run != null) {
                    val session = requireNotNull(member(run, "getSession"))
                    val components = field(session, "components") as List<*>
                    require(components.size <= 128)
                    component = components.singleOrNull {
                        it?.javaClass?.name == "com.eignex.klause.theory.qflra.ExactLiraSearchComponent"
                    }
                    val exact = component
                    if (exact != null) {
                        try {
                            probe = LinearQueryInputProbe(exact, samples)
                            probeStatus = "ready"
                        } catch (_: ReflectiveOperationException) {
                            probeStatus = "unsupported-input-fields"
                        }
                    } else probeStatus = "no-exact-lira-component"
                    val advance = method(run, "advance", 0)
                    while (result == null && !original()) result = advance.invoke(run)
                }
                resultClass = result?.javaClass?.simpleName ?: "containing-deadline"
            }
        } finally {
            timedNs = System.nanoTime() - started
            observing = false
            (run as? AutoCloseable)?.close()
        }
        check(!output.exceeded) { "diagnostic stream output cap" }
        val snapshot = samples.snapshot()
        val analysis = QueryAnalysisBudget()
        val metadata = LinearQueryMetadata(analysis)
        val observations = snapshot.samples.mapIndexed { index, sample ->
            val details = try {
                analysis.poll()
                analysis.step()
                val input = metadata.inspect(sample.state, requireNotNull(component))
                val geometry = LinearQueryEligibility.inspect(input, analysis::step)
                val result = buildJsonObject {
                    put("geometry", Reports.json.parseToJsonElement(Reports.json.encodeToString(geometry)))
                    for (name in listOf("matrixRevision", "boundRevision", "objectiveRevision", "rowRevision", "popRevision")) {
                        put(name, field(sample.state, name) as Long)
                    }
                    put("depth", (field(sample.state, "scopeMarks") as List<*>).size)
                }
                analysis.poll()
                result
            } catch (_: Exception) {
                buildJsonObject { put("status", "inconclusive-metadata-schema-or-analysis-cap") }
            }
            buildJsonObject {
                put("index", index)
                put("transition", sample.transition)
                put("numericStorageUnits", sample.units)
                put("analysis", details)
            }
        }
        val work = state?.let { member(it, "getWork") as? OpenTheoryWorkSink }?.snapshot()
        val smt = component?.let { field(it, "smtStats") }?.let { member(it, "snapshot") as? SmtStats }
        val declines = smt?.continuation?.declines.orEmpty()
        require(declines.size <= 128)
        val report = buildJsonObject {
            put("head", Reports.readGitSha())
            put("sourceSha256", options.getValue("sha256"))
            put("sourceBytes", bytes.size)
            sourceCounts?.let { put("parsedSourceCounts", it) }
            put("suite", options.getValue("suite"))
            put("problem", options.getValue("problem"))
            put("entry", "public-open-theory-run; compilation-included")
            put("cliPrefixEquivalent", false)
            put("ordinaryTimingOrVerdictCredit", false)
            put("solverOrWitnessWorkAfterTiming", false)
            put("budgetMs", BUDGET_MS)
            put("timedNs", timedNs)
            put("resultClass", resultClass)
            put("probeStatus", probeStatus)
            put("pollsBeforeProbe", unboundPolls)
            put("observerFailures", stop.observerFailures)
            put("matchingInputReads", probe?.matchingReads ?: 0L)
            put("probeSkips", JsonObject(probe?.coverage().orEmpty().mapValues { JsonPrimitive(it.value) }))
            put("transitions", samples.transitions)
            put("repeatedIdentity", samples.repeatedIdentity)
            put("uncachedWeight", samples.uncachedWeight)
            put("invalidWeight", samples.invalidWeight)
            put("oversizedWeight", samples.oversizedWeight)
            put("inconsistentWeight", samples.inconsistentWeight)
            put("skippedCheckpoint", samples.skippedCheckpoint)
            put("retainedUniqueInputs", snapshot.samples.size)
            put("retainedNumericStorageUnits", snapshot.retainedUnits)
            put("maxInputNumericStorageUnits", LinearQueryInputSamples.MAX_STATE_UNITS)
            put("maxAggregateNumericStorageUnits", LinearQueryInputSamples.MAX_TOTAL_UNITS)
            put("weakTransitionMarker", true)
            put("lastRetainedTransition", snapshot.lastRetainedTransition)
            put("lastSampleIndex", snapshot.lastSampleIndex)
            put("openTheoryChecks", work?.openTheoryChecks ?: 0L)
            put("openBoolDecisions", work?.openBoolDecisions ?: 0L)
            put("reportedContinuationDeclines", JsonObject(declines.mapValues { JsonPrimitive(it.value) }))
            put("observations", JsonArray(observations))
            put("interpretation", "opportunistic static eligibility only; no link to individual dimension declines")
        }
        val serialized = report.toString()
        analysis.poll()
        writeBounded(out.resolve("eligibility.json"), serialized)
        println("Observed ${snapshot.samples.size} immutable numerical inputs; source ${options.getValue("sha256")}")
        check(!output.exceeded) { "diagnostic stream output cap" }
    }

    private fun verifyLoadedSolver(out: File) {
        val loaded = File(OpenTheoryPipeline.javaClass.protectionDomain.codeSource.location.toURI())
        val installed = SolverInvocation.klauseCliBin().canonicalFile.parentFile.parentFile.resolve("lib/${loaded.name}")
        require(installed.isFile && sha256(loaded.readBytes()) == sha256(installed.readBytes()))
        writeBounded(out.resolve("loaded-solver.json"), buildJsonObject {
            put("path", loaded.path)
            put("sha256", sha256(loaded.readBytes()))
            put("installedCliPath", installed.path)
            put("runtime", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map { JsonPrimitive(it) }))
            put("javaVersion", System.getProperty("java.version"))
            put("processors", Runtime.getRuntime().availableProcessors())
        }.toString())
    }

    private fun launch(args: Array<String>, out: File) {
        val command = arrayListOf(File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")
        for (name in listOf("klause.workspace.root", "klause.bench.corpusCache")) {
            System.getProperty(name)?.let { command += "-D$name=$it" }
        }
        command += listOf("-cp", System.getProperty("java.class.path"), javaClass.name)
        command += args
        val launcher = buildJsonObject {
            put("command", JsonArray(command.map { JsonPrimitive(it) }))
        }.toString()
        require(launcher.toByteArray().size <= PARENT_OUTPUT_RESERVE - 4096L)
        out.resolve("launcher.json").writeText(launcher)
        val builder = ProcessBuilder(command).redirectOutput(out.resolve("measured.stdout"))
            .redirectError(out.resolve("measured.stderr"))
        builder.environment()["KLAUSE_QUERY_ELIGIBILITY_CHILD"] = "1"
        val process = builder.start()
        var timeout = false
        try {
            timeout = !process.waitFor(120L, TimeUnit.SECONDS)
        } finally {
            val forced = process.isAlive
            if (forced) { process.destroyForcibly(); process.waitFor() }
            val transport = buildJsonObject {
                put("timeout", timeout)
                put("forced", forced)
                put("exit", process.exitValue())
            }.toString()
            require(transport.toByteArray().size <= 4096)
            out.resolve("transport.json").writeText(transport)
        }
        val names = listOf("launcher.json", "transport.json", "measured.stdout", "measured.stderr", "build-provenance.json",
            "loaded-solver.json", "eligibility.json")
        require(names.sumOf { out.resolve(it).length() } <= MAX_OUTPUT_BYTES)
        check(!timeout && process.exitValue() == 0) { "eligibility observation JVM failed" }
    }

    private fun writeBounded(file: File, text: String) {
        output.write(file, text)
    }

    private fun method(owner: Any, prefix: String, count: Int): Method = owner.javaClass.declaredMethods.single {
        it.name.startsWith(prefix) && it.parameterCount == count
    }.apply { isAccessible = true }
    private fun member(owner: Any, name: String): Any? = owner.javaClass.methods.singleOrNull {
        it.name == name && it.parameterCount == 0
    }?.invoke(owner)
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private const val MAX_OUTPUT_BYTES = 1024L * 1024L
    private const val PARENT_OUTPUT_RESERVE = 128L * 1024L
    private val output = QueryDiagnosticOutput(MAX_OUTPUT_BYTES - PARENT_OUTPUT_RESERVE)
    private const val MAX_SOURCE_BYTES = 1024 * 1024
    private const val BUDGET_MS = 10_000L
}
