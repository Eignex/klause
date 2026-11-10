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
        parameters(options)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(1_048_577) }
        require(bytes.size <= 1_048_576 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.mps").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        inspect(options, bytes, out)
    }

    internal fun parameters(options: Map<String, String>): List<String> {
        val required = setOf("suite", "problem", "sha256", "seed", "handoff")
        require(options.keys.containsAll(required) && (options.keys - required).all {
            it in setOf("timeout-ms", "captures", "cutoff-bits", "capture-worker", "capture-route", "capture-guidance",
                "portfolio-policy", "portfolio-progress", "capture-mode")
        })
        val mode = options["capture-mode"] ?: "portfolio"
        require(mode in setOf("portfolio", "joint-before"))
        val preset = options["portfolio-policy"] ?: if (mode == "joint-before") "complete" else "retained"
        require(preset in setOf("ordinary", "retained", "complete")) { "unsupported portfolio policy: $preset" }
        val handoff = options.getValue("handoff").toBooleanStrict()
        val progress = options["portfolio-progress"]?.toBooleanStrict() ?: (mode == "portfolio")
        if (mode == "joint-before") {
            require(handoff && preset == "complete" && !progress)
            require(options.keys.none { it in setOf("cutoff-bits", "capture-worker", "capture-route",
                "capture-guidance") })
            return listOf("companion-integers=true", "companion-active-ms=4000",
                "companion-after-incumbent=false", "companion-admission=ordinary", "companion-repair=fixed",
                "source-incumbent-completion=true", "portfolio-progress=false",
                "portfolio-proof-workers=complete", "portfolio-proof-share=0.5")
        }
        if (preset == "ordinary") {
            require(!handoff) { "ordinary portfolio policy requires handoff=false" }
            return listOf("portfolio-progress=$progress")
        }
        return listOf("companion-integers=true", "companion-active-ms=4000",
            "companion-after-incumbent=true", "companion-admission=progress-funded", "companion-repair=fixed",
            "source-incumbent-completion=$handoff", "portfolio-progress=$progress", "portfolio-reward=ordinary",
            "phase-retention=0.25", "reseed-stale-threshold=3", "portfolio-probes=ordinary",
            "portfolio-proof-workers=$preset", "portfolio-proof-share=0.5")
    }

    internal fun inspect(options: Map<String, String>, bytes: ByteArray, out: File) {
        val pairs = parameters(options)
        if (options["capture-mode"] == "joint-before") {
            JointRepairLpCapture.inspect(options, bytes, out)
            return
        }
        val timeout = options["timeout-ms"]?.toLong() ?: 30_000L
        val limit = options["captures"]?.toInt() ?: 1
        val captureGuidance = options["capture-guidance"]?.toBooleanStrict() ?: false
        val seed = options.getValue("seed").toLong()
        val cutoffBits = options["cutoff-bits"]?.toLong()
        val captureWorker = options["capture-worker"] ?: "bt/lp-default"
        val captureRoute = options["capture-route"] ?: "any"
        require(captureWorker in setOf("bt/lp-default", "any"))
        require(captureRoute in setOf("any", "SESSION", "SCOPED_FLOAT", "SCOPED_CONTINUATION"))
        require(timeout in 1L..30_000L && limit in 0..4)
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
                put("portfolioPolicy", options["portfolio-policy"] ?: "retained")
                put("finiteEngine", FiniteEngine.MIXED.id)
                put("parameters", JsonArray(pairs.map(::JsonPrimitive)))
                put("executionNs", System.nanoTime() - executionStarted)
                put("containingTimeoutMs", timeout)
                put("cancelled", stop())
                put("sourceVerdictClaim", false)
                put("fullWarmReplayQualified", false)
                put("queryScope", "solve sessions and scoped floating queries; other LP calls omitted")
            }.toString())
            return
        }
        val policy = SourceExecutionPolicy()
        val window = QueryWindow(captureWorker, limit)
        var retained = 0
        var eventCount = 0
        var eventsDropped = 0
        var queryObserverNs = 0L
        var guidanceObserverNs = 0L
        var guidanceEvents = 0
        var pointVersion = 0L
        var point: JsonObject? = null
        var producer: String? = null
        out.resolve("guidance.jsonl").bufferedWriter().use { guidance ->
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
                            val observation = window.observe(worker, { get(query, "Owner") }) {
                                (captureRoute == "any" || get(query, "Route").toString() == captureRoute) &&
                                    (cutoffBits == null || cutoff?.toBits() == cutoffBits)
                            }
                            if (observation != null) {
                                val owner = observation.owner
                                val selected = observation.capture
                                val payload = if (selected) capture(query, stop) else null
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
                                    put("route", get(query, "Route").toString())
                                    put("claim", call(query, "getClaim")?.toString())
                                    put("workLimit", get(query, "WorkLimit").toString())
                                    put("explicitAllowance", get(query, "ExplicitAllowance") as Boolean)
                                    put("iterationLimit", get(query, "IterationLimit").toString())
                                    put("initialQuantum", call(query, "getInitialQuantum")?.toString())
                                    put("retainFundingStop", get(query, "RetainFundingStop") as Boolean)
                                    put("refactorUpdateLimit", get(query, "RefactorUpdateLimit").toString())
                                    put("trackDegeneracy", get(query, "TrackDegeneracy") as Boolean)
                                    put("retained", get(query, "Retained") as Boolean)
                                    put("componentSplit", get(query, "ComponentSplit") as Boolean)
                                    put("floatOffset", get(query, "FloatOffset").toString())
                                    put("opaqueFloatAcceptance", get(query, "HasFloatAcceptance") as Boolean)
                                    put("pricing", NumericalResidualProfile.json(get(query, "Pricing")))
                                    put("refinementLimits",
                                        NumericalResidualProfile.json(call(query, "getRefinementLimits")))
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
                            }
                        } finally {
                            queryObserverNs += System.nanoTime() - started
                        }
                    }
                    var observed = request
                    if (limit > 0) {
                        val attach = request.javaClass.methods.single {
                            it.name.startsWith("withPortfolioLpQueryObserver") && it.parameterCount == 1
                        }
                        observed = attach.invoke(observed, observer) as SourceExecutionRequest
                    }
                    if (captureGuidance) {
                        val guidanceObserver: (String, Any) -> Unit = { worker, observation ->
                            val started = System.nanoTime()
                            try {
                                if (captureWorker == "any" || worker == captureWorker) {
                                    guidanceEvents++
                                    guidance.appendLine(buildJsonObject {
                                        put("worker", worker)
                                        put("elapsedNs", started - executionStarted)
                                        put("latestCheckedSourcePointVersion", pointVersion)
                                        put("latestPointProducer", producer)
                                        if (get(observation, "Kind").toString() == "IMPORT") {
                                            put("latestCheckedSourcePoint", point ?: JsonNull)
                                        }
                                        for (name in listOf("Kind", "Preferred", "Site", "Version", "Objective",
                                            "LocalObjective", "LocalExactObjective", "LastCutoff", "RootPending",
                                            "Decision", "Level")) {
                                            put(name.replaceFirstChar { it.lowercaseChar() },
                                                call(observation, "get$name")?.toString())
                                        }
                                    }.toString())
                                    guidance.flush()
                                }
                            } finally {
                                guidanceObserverNs += System.nanoTime() - started
                            }
                        }
                        val attach = observed.javaClass.methods.single {
                            it.name.startsWith("withPortfolioGuidanceObserver") && it.parameterCount == 1
                        }
                        observed = attach.invoke(observed, guidanceObserver) as SourceExecutionRequest
                    }
                    val result = SourceSetup.execute(observed, SourceExecutionCallbacks(
                        onLinearSourceIncumbent = { witness ->
                            pointVersion++
                            val text = buildJsonObject {
                                put("version", pointVersion)
                                put("objective", witness.objective?.toString())
                                put("exact", witness.exact)
                                put("values", JsonArray(witness.values.map { JsonPrimitive(it.toString()) }))
                            }.toString()
                            point = if (text.length <= 65_536) {
                                Reports.json.parseToJsonElement(text) as JsonObject
                            } else null
                        }, finite = FiniteSolveCallbacks({}, { producer = it.workerLabel })))
                    out.resolve("result.json").writeText(buildJsonObject {
                        put("nativeResultKind", result.javaClass.simpleName)
                        put("sourceSemantics", original.semantics.toString())
                        put("portfolioPolicy", options["portfolio-policy"] ?: "retained")
                        put("finiteEngine", FiniteEngine.MIXED.id)
                        put("parameters", JsonArray(pairs.map(::JsonPrimitive)))
                        put("seed", seed)
                        put("containingTimeoutMs", timeout)
                        put("queryMetadataLimit", 128)
                        put("captureValueLimit", 65_536)
                        put("captureByteLimit", 1_048_576)
                        put("queries", window.queries)
                        put("workerQueries", window.workerQueries)
                        put("unselectedWorkerQueries", window.unselectedWorkerQueries)
                        put("captureAttempts", window.captureAttempts)
                        put("retainedCaptures", retained)
                        put("metadataDropped", window.metadataDropped)
                        put("metadataScope", "capture-worker; unselected callbacks counted separately")
                        put("eventsDropped", eventsDropped)
                        put("queryObserverNs", queryObserverNs)
                        put("guidanceObserverNs", guidanceObserverNs)
                        put("guidanceEvents", guidanceEvents)
                        put("captureGuidance", captureGuidance)
                        put("captureQueries", limit > 0)
                        put("guidanceObjectiveUnits", "native-minimized")
                        put("guidanceExactLocalBitsPerPart", 2048)
                        put("guidanceImportAndLocalLimitPerWorker", 16)
                        put("guidanceAttemptAndCommitLimitPerWorker", 128)
                        put("guidanceBranchWindow", 8)
                        put("guidancePreferenceScope", "configured preference; no counterfactual ordering evaluated")
                        put("guidanceCutoffScope", "last scalar read; exact cutoff not captured")
                        put("executionNs", System.nanoTime() - executionStarted)
                        put("executionScope", "parse, source setup, execution and retirement; " +
                            "excludes input fetch and final output")
                        put("queryObserverCostScope", "subset of containing execution; " +
                            "other observation costs are also inside execution")
                        put("sourceVerdictClaim", false)
                        put("fullWarmReplayQualified", false)
                        put("queryScope", "solve sessions and scoped floating queries; other LP calls omitted")
                        put("cancelled", stop())
                    }.toString())
                }
            }
        }
    }

    internal class QueryWindow(private val worker: String, private val captureLimit: Int) {
        private val owners = IdentityHashMap<Any, Int>()
        var queries = 0
            private set
        var workerQueries = 0
            private set
        var unselectedWorkerQueries = 0
            private set
        var metadataDropped = 0
            private set
        var captureAttempts = 0
            private set

        init {
            require(captureLimit in 0..4)
        }

        fun observe(worker: String, owner: () -> Any, selected: () -> Boolean): Observation? {
            queries++
            if (this.worker != "any" && worker != this.worker) {
                unselectedWorkerQueries++
                return null
            }
            workerQueries++
            val identity = owner()
            if (owners.size >= 64 && !owners.containsKey(identity)) {
                metadataDropped++
                return null
            }
            val id = owners.computeIfAbsent(identity) { owners.size }
            val capture = captureAttempts < captureLimit && selected()
            if (workerQueries > 124 && !capture) {
                metadataDropped++
                return null
            }
            if (capture) captureAttempts++
            return Observation(id, capture)
        }

        data class Observation(val owner: Int, val capture: Boolean)
    }

    internal fun capture(query: Any, stop: Cancellation): ByteArray? {
        val model = get(query, "Model")
        if ((get(model, "M") as Int) > 4096 ||
            (get(model, "N") as Int).toLong() + (get(model, "M") as Int) > 8192) return null
        return call(query, "captureExactWithin", "finite-portfolio-query", 65_536, 1_048_576, 4096, stop) as ByteArray?
    }

    internal fun writeBasis(query: Any, file: File?): String? {
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
