package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteSolveCallbacks
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceExecutionPolicy
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.util.Cancellation
import java.io.File
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object FinitePortfolioLpCapture {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(1_048_577) }
        require(bytes.size <= 1_048_576 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.mps").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        inspect(options, bytes, out)
    }

    internal fun inspect(options: Map<String, String>, bytes: ByteArray, out: File) {
        val required = setOf("suite", "problem", "sha256", "seed", "handoff")
        require(options.keys.containsAll(required) && (options.keys - required).all {
            it in setOf("timeout-ms", "captures", "cutoff-bits", "capture-worker")
        })
        val timeout = options["timeout-ms"]?.toLong() ?: 30_000L
        val limit = options["captures"]?.toInt() ?: 1
        val seed = options.getValue("seed").toLong()
        val handoff = options.getValue("handoff").toBooleanStrict()
        val cutoffBits = options["cutoff-bits"]?.toLong()
        val captureWorker = options["capture-worker"] ?: "bt/lp-default"
        require(captureWorker in setOf("bt/lp-default", "any"))
        require(timeout in 1L..30_000L && limit in 1..4)
        require(bytes.size <= 1_048_576 && sha256(bytes) == options.getValue("sha256"))
        out.mkdirs()
        out.resolve("source.mps").writeBytes(bytes)
        val sourceHash = sha256(bytes)
        val executionStarted = System.nanoTime()
        val stop = Cancellation.after(timeout.milliseconds)
        val original = requireNotNull(Mps.parse(bytes.decodeToString()).toLinearSourceProblem(cancellation = stop))
        require(original.variables.size <= 4096 && original.rows.size <= 4096)
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(original, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED,
            maximize = original.objective?.sense == ObjectiveSense.MAXIMIZE, cancellation = stop))
        if (SourceSetup.executionKind(invocation) != SourceExecutionKind.FINITE) {
            out.resolve("result.json").writeText(buildJsonObject {
                put("captureDeclined", "finite source route unavailable after preparation")
                put("executionKind", SourceSetup.executionKind(invocation).name)
                put("executionNs", System.nanoTime() - executionStarted)
                put("containingTimeoutMs", timeout)
                put("cancelled", stop())
                put("sourceVerdictClaim", false)
                put("fullWarmReplayQualified", false)
            }.toString())
            return
        }
        val pairs = listOf("companion-integers=true", "companion-active-ms=4000",
            "companion-after-incumbent=true", "companion-admission=progress-funded", "companion-repair=fixed",
            "source-incumbent-completion=$handoff", "portfolio-progress=true", "portfolio-reward=ordinary",
            "phase-retention=0.25", "reseed-stale-threshold=3", "portfolio-probes=ordinary",
            "portfolio-proof-workers=retained", "portfolio-proof-share=0.5")
        val policy = SourceExecutionPolicy()
        val owners = IdentityHashMap<Any, Int>()
        var queries = 0
        var attempts = 0
        var retained = 0
        var metadataDropped = 0
        var eventCount = 0
        var eventsDropped = 0
        var queryObserverNs = 0L
        var pointVersion = 0L
        var point: JsonObject? = null
        var producer: String? = null
        out.resolve("queries.jsonl").bufferedWriter().use { records ->
            out.resolve("events.jsonl").bufferedWriter().use { events ->
                val request = SourceExecutionRequest(invocation, policy, ExecutionOptions.parse(pairs),
                    finiteRequest = { shape -> policy.finiteRequest(shape, FiniteEngine.MIXED,
                        cancellation = stop, randomSeed = seed,
                        onPortfolioEvent = { worker, event ->
                            if (event.javaClass.simpleName in setOf("PortfolioDiagnostic", "CompanionComparison",
                                    "WorkerAdmission")) {
                                if (eventCount++ < 1024) events.appendLine(buildJsonObject {
                                    put("worker", worker)
                                    put("event", event.toString().take(4096))
                                }.toString()) else eventsDropped++
                            }
                        }) }, cores = 1, randomSeed = seed, timeout = stop, cancellation = stop)
                val observer: (String, Double?, Any) -> Unit = { worker, cutoff, query ->
                    val started = System.nanoTime()
                    try {
                        queries++
                        if (owners.size < 64 || owners.containsKey(get(query, "Owner"))) {
                            val owner = owners.computeIfAbsent(get(query, "Owner")) { owners.size }
                            val selected = (captureWorker == "any" || worker == captureWorker) && attempts < limit &&
                                (cutoffBits == null || cutoff?.toBits() == cutoffBits)
                            if (queries <= 124 || selected) {
                                val payload = if (selected) capture(query, stop).also { attempts++ } else null
                                val file = payload?.let {
                                    "query-$owner-${get(query, "Sequence")}.klexact".also { name ->
                                        out.resolve(name).writeBytes(it)
                                        point?.let { value ->
                                            out.resolve("$name-point.json").writeText(value.toString())
                                        }
                                        retained++
                                    }
                                }
                                val basisHash = if (selected) writeBasis(query,
                                    file?.let { out.resolve("$it-basis.json") }) else null
                                records.appendLine(buildJsonObject {
                                    put("worker", worker)
                                    put("owner", owner)
                                    put("sequence", get(query, "Sequence").toString())
                                    put("sourceSha256", sourceHash)
                                    put("latestCheckedSourcePointVersion", pointVersion)
                                    put("latestPointProducer", producer)
                                    put("lastObservedExternalCutoffBits", cutoff?.toBits()?.toString())
                                    put("lastObservedExternalCutoff", cutoff?.toString())
                                    put("claim", get(query, "Claim").toString())
                                    put("workLimit", get(query, "WorkLimit").toString())
                                    put("retained", get(query, "Retained") as Boolean)
                                    put("componentSplit", get(query, "ComponentSplit") as Boolean)
                                    put("floatOffset", get(query, "FloatOffset").toString())
                                    put("opaqueFloatAcceptance", get(query, "HasFloatAcceptance") as Boolean)
                                    put("pricing", NumericalResidualProfile.json(get(query, "Pricing")))
                                    put("refinementLimits",
                                        NumericalResidualProfile.json(get(query, "RefinementLimits")))
                                    put("selected", selected)
                                    put("capture", file)
                                    put("captureSha256", payload?.let(::sha256))
                                    put("basisSha256", basisHash)
                                    put("sourcePointFile", file?.takeIf { point != null }?.let { "$it-point.json" })
                                    put("sourcePointSha256", point?.takeIf { file != null }?.let {
                                        sha256(it.toString().encodeToByteArray())
                                    })
                                    put("captureDeclined", selected && payload == null)
                                    put("sourceVerdictClaim", false)
                                }.toString())
                                records.flush()
                            } else metadataDropped++
                        } else metadataDropped++
                    } finally {
                        queryObserverNs += System.nanoTime() - started
                    }
                }
                val attach = request.javaClass.methods.single {
                    it.name.startsWith("withPortfolioLpQueryObserver") && it.parameterCount == 1
                }
                val observed = attach.invoke(request, observer) as SourceExecutionRequest
                val result = SourceSetup.execute(observed, SourceExecutionCallbacks(
                    onLinearSourceIncumbent = { witness ->
                        pointVersion++
                        val text = buildJsonObject {
                            put("version", pointVersion)
                            put("objective", witness.objective?.toString())
                            put("exact", witness.exact)
                            put("values", JsonArray(witness.values.map { JsonPrimitive(it.toString()) }))
                        }.toString()
                        point = if (text.length <= 65_536) Reports.json.parseToJsonElement(text) as JsonObject else null
                    }, finite = FiniteSolveCallbacks({}, { producer = it.workerLabel })))
                out.resolve("result.json").writeText(buildJsonObject {
                    put("nativeResultKind", result.javaClass.simpleName)
                    put("sourceSemantics", original.semantics.toString())
                    put("parameters", JsonArray(pairs.map(::JsonPrimitive)))
                    put("seed", seed)
                    put("containingTimeoutMs", timeout)
                    put("queryMetadataLimit", 128)
                    put("captureValueLimit", 65_536)
                    put("captureByteLimit", 1_048_576)
                    put("queries", queries)
                    put("captureAttempts", attempts)
                    put("retainedCaptures", retained)
                    put("metadataDropped", metadataDropped)
                    put("eventsDropped", eventsDropped)
                    put("queryObserverNs", queryObserverNs)
                    put("executionNs", System.nanoTime() - executionStarted)
                    put("executionScope", "parse, source setup, execution and retirement; " +
                        "excludes input fetch and final output")
                    put("queryObserverCostScope", "subset of containing execution; " +
                        "other observation costs are also inside execution")
                    put("sourceVerdictClaim", false)
                    put("fullWarmReplayQualified", false)
                    put("cancelled", stop())
                }.toString())
            }
        }
    }

    internal fun capture(query: Any, stop: Cancellation): ByteArray? {
        val model = get(query, "Model")
        if ((get(model, "M") as Int) > 4096 ||
            (get(model, "N") as Int).toLong() + (get(model, "M") as Int) > 8192) return null
        val state = call(model, "getExactState") ?: return null
        val settingsType = Class.forName("com.eignex.klause.lp.engine.LpReplaySettings")
        val settings = settingsType.constructors.single { it.parameterCount == 9 }.newInstance(
            "finite-portfolio-query", get(get(query, "Pricing"), "TieSeed"),
            Class.forName("com.eignex.klause.lp.engine.LpReplaySolverKind").enumConstants.single {
                it.toString() == "PERSISTENT"
            }, get(query, "ComponentSplit"), 0, 0, get(query, "WorkLimit"), refactorLimit(), false)
        val companion = Class.forName("com.eignex.klause.lp.engine.LpExactCapture").getField("Companion").get(null)
        val captured = call(companion, "capture", state, settings, emptyList<Any>(), 4096) ?: return null
        return call(captured, "encodeWithin", 65_536, 1_048_576, stop) as ByteArray?
    }

    private fun refactorLimit(): Int = Class.forName("com.eignex.klause.lp.engine.RevisedSimplexKt")
        .getDeclaredField("DEFAULT_REFACTOR_UPDATE_LIMIT").getInt(null)

    private fun writeBasis(query: Any, file: File?): String? {
        if (file == null) return null
        val model = get(query, "Model")
        if ((get(model, "N") as Int).toLong() + (get(model, "M") as Int) > 8192) return null
        val basis = call(query, "captureBasis")
        val text = if (basis == null) JsonNull.toString() else buildJsonObject {
            put("headings", JsonArray((get(basis, "BasicVars") as IntArray).map(::JsonPrimitive)))
            put("statuses", JsonArray((get(basis, "Status") as Array<*>).map { JsonPrimitive(it.toString()) }))
            put("captureEligible", get(basis, "CaptureEligible") as Boolean)
        }.toString()
        file.writeText(text)
        return sha256(text.encodeToByteArray())
    }

    private fun get(owner: Any, name: String): Any = requireNotNull(call(owner, "get$name"))

    @Suppress("SpreadOperator")
    private fun call(owner: Any, name: String, vararg arguments: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *arguments)
}
