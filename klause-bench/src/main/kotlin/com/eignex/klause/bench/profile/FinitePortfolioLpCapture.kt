package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.lab.configureLabPaths
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteSolveCallbacks
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceExecutionPolicy
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.util.ExecutionControl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.milliseconds

internal object FinitePortfolioLpCapture {
    @JvmStatic
    fun main(args: Array<String>) {
        configureLabPaths()
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
                "portfolio-policy", "portfolio-progress", "capture-mode", "inspect-rays",
                "source-refutation-center-step", "capture-authority", "capture-cut-policy",
                "capture-neighborhoods", "capture-alns")
        })
        val mode = options["capture-mode"] ?: "portfolio"
        require(mode in setOf("portfolio", "joint-before", "joint-ordinary", "joint-handoff"))
        val captureJoint = mode == "joint-ordinary" || mode == "joint-handoff"
        if ("capture-authority" in options || "capture-cut-policy" in options) {
            require(mode == "joint-before")
            require((options["capture-authority"] ?: "nominal") in setOf("nominal", "accepted"))
            require((options["capture-cut-policy"] ?: "ordinary") in setOf("ordinary", "selected"))
        }
        val centerStep = options["source-refutation-center-step"]?.let {
            require(captureJoint) {
                "source-refutation-center-step requires joint-ordinary capture or joint-handoff capture"
            }
            it.toBooleanStrict()
        }
        if ("inspect-rays" in options) {
            require(mode == "joint-before" && options["inspect-rays"] in setOf("true", "false"))
        }
        val preset = options["portfolio-policy"] ?: if (mode != "portfolio") "complete" else "retained"
        require(preset in setOf("ordinary", "retained", "complete")) { "unsupported portfolio policy: $preset" }
        val handoff = options.getValue("handoff").toBooleanStrict()
        val progress = options["portfolio-progress"]?.toBooleanStrict() ?: (mode == "portfolio")
        if (options["capture-neighborhoods"]?.toBooleanStrict() == true ||
            options["capture-alns"]?.toBooleanStrict() == true) {
            require(!(options["capture-neighborhoods"] == "true" && options["capture-alns"] == "true"))
            require(mode == "portfolio" && preset == "ordinary" && !handoff && !progress &&
                options["captures"] == "0" && options["capture-guidance"]?.toBooleanStrict() != true) {
                "neighborhood capture requires ordinary automatic policy without other captures or progress logging"
            }
        }
        if (mode == "joint-before" || captureJoint) {
            require(handoff && preset == "complete" && !progress)
            require(options.keys.none {
                it in setOf("cutoff-bits", "capture-worker", "capture-route",
                    "capture-guidance")
            })
            if (captureJoint) require((options["captures"]?.toInt() ?: 0) == 0)
            return listOf("companion-integers=${mode != "joint-handoff"}", "companion-active-ms=4000",
                "companion-after-incumbent=${mode == "joint-handoff"}",
                "companion-admission=ordinary", "companion-repair=fixed",
                "source-incumbent-completion=true", "portfolio-progress=false",
                "portfolio-proof-workers=complete", "portfolio-proof-share=0.5") +
                listOfNotNull(centerStep?.let { "source-refutation-center-step=$it" })
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
        val captureJoint = options["capture-mode"] in setOf("joint-ordinary", "joint-handoff")
        val limit = options["captures"]?.toInt() ?: if (captureJoint) 0 else 1
        val captureGuidance = options["capture-guidance"]?.toBooleanStrict() ?: false
        val captureAlns = options["capture-alns"]?.toBooleanStrict() ?: false
        val captureDiagnostics = captureAlns || (options["capture-neighborhoods"]?.toBooleanStrict() ?: false)
        val neighborhoods = out.resolve(if (captureAlns) "alns-iterations.jsonl" else "neighborhoods.jsonl")
        if (captureDiagnostics) neighborhoods.writeText("")
        var neighborhoodRecords = 0
        var neighborhoodObserverNs = 0L
        var neighborhoodEncodedBytes = 0L
        var neighborhoodEncodingOmitted = 0
        val seed = options.getValue("seed").toLong()
        val cutoffBits = options["cutoff-bits"]?.toLong()
        val captureWorker = options["capture-worker"] ?: "bt/lp-default"
        val captureRoute = options["capture-route"] ?: "any"
        require(captureWorker in setOf("bt/lp-default", "any"))
        require(captureRoute in setOf("any", "SESSION", "SCOPED_FLOAT", "SCOPED_CONTINUATION"))
        require(timeout in 1L..(if (captureDiagnostics) 60_000L else 30_000L) && limit in 0..4)
        require(bytes.size <= 1_048_576 && sha256(bytes) == options.getValue("sha256"))
        out.mkdirs()
        out.resolve("source.mps").writeBytes(bytes)
        val sourceHash = sha256(bytes)
        val executionStarted = System.nanoTime()
        val stop = ExecutionControl.after(timeout.milliseconds)
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
        val policy = SourceExecutionPolicy(explicitPresolve = captureDiagnostics)
        val preparation = if (captureDiagnostics) PresolveConfig.NONE else PresolveConfig.DEFAULT
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
        var jointRecords = 0
        var jointObserverNs = 0L
        out.resolve("joint-repair.jsonl").bufferedWriter().use { joint ->
            out.resolve("guidance.jsonl").bufferedWriter().use { guidance ->
                out.resolve("queries.jsonl").bufferedWriter().use { records ->
                    out.resolve("events.jsonl").bufferedWriter().use { events ->
                        val request = SourceExecutionRequest(invocation, policy, ExecutionOptions.parse(pairs),
                            presolveConfig = if (captureDiagnostics) preparation else null,
                            finiteRequest = { shape ->
                                policy.finiteRequest(shape, FiniteEngine.MIXED,
                                    presolveConfig = preparation, cancellation = stop, randomSeed = seed,
                                    onPortfolioEvent = if (captureDiagnostics) null else { worker, event ->
                                        if (event.javaClass.simpleName in setOf(
                                                "PortfolioDiagnostic", "CompanionComparison", "WorkerAdmission")) {
                                            if (eventCount++ < 1024) events.appendLine(buildJsonObject {
                                                put("worker", worker)
                                                put("event", event.toString().take(4096))
                                            }.toString()) else eventsDropped++
                                        }
                                    })
                            }, cores = 1, randomSeed = seed, timeout = stop, cancellation = stop)
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
                        if (captureDiagnostics) {
                            val neighborhoodObserver: (String, Long, Any) -> Unit = { worker, workerSeed, value ->
                                val started = System.nanoTime()
                                try {
                                    val text = (if (captureAlns) alnsMetadata(value) else
                                        neighborhoodMetadata(worker, workerSeed, value)).toString()
                                    val encodedBytes = text.encodeToByteArray().size.toLong() + 1L
                                    if (encodedBytes <= 65_536L - neighborhoodEncodedBytes) {
                                        neighborhoods.appendText(text + "\n")
                                        neighborhoodRecords++
                                        neighborhoodEncodedBytes += encodedBytes
                                    } else neighborhoodEncodingOmitted++
                                } finally {
                                    neighborhoodObserverNs += System.nanoTime() - started
                                }
                            }
                            val method = if (captureAlns) "withPortfolioAlnsObserver"
                            else "withPortfolioNeighborhoodObserver"
                            val attach = observed.javaClass.methods.single {
                                it.name.startsWith(method) && it.parameterCount == 1
                            }
                            val sink = if (captureAlns) { value: Any -> neighborhoodObserver("", 0L, value) }
                            else null
                            observed = attach.invoke(observed, sink ?: neighborhoodObserver) as SourceExecutionRequest
                        }
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
                        if (captureJoint) {
                            val jointObserver: (String, Long, Any) -> Unit = { worker, workerSeed, observation ->
                                val started = System.nanoTime()
                                try {
                                    val metadata = jointMetadata(worker, workerSeed, sourceHash, observation)
                                    joint.appendLine(metadata.toString())
                                    joint.flush()
                                    jointRecords++
                                } finally {
                                    jointObserverNs += System.nanoTime() - started
                                }
                            }
                            val attach = observed.javaClass.methods.single {
                                it.name.startsWith("withPortfolioJointRepairObserver") && it.parameterCount == 1
                            }
                            observed = attach.invoke(observed, jointObserver) as SourceExecutionRequest
                        }
                        val callbacks = FiniteSolveCallbacks({}, { producer = it.workerLabel })
                        val sourceCallbacks = if (captureDiagnostics) SourceExecutionCallbacks(finite = callbacks)
                        else SourceExecutionCallbacks(onLinearSourceIncumbent = { witness ->
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
                        }, finite = callbacks)
                        val result = SourceSetup.execute(observed, sourceCallbacks)
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
                            put("jointCapture", captureJoint)
                            put("jointRecords", jointRecords)
                            put("jointObserverNs", jointObserverNs)
                            put("jointCaptureLimits",
                                "64 records, 16384 entries, 1048576 modeled bytes, 4096 scalar bits")
                            val finite = (result as? SourceExecutionResult.Finite)?.result?.outcome
                                as? FiniteSolveOutcome.Completed
                            if (captureJoint || captureDiagnostics) {
                                put("sourceSha256", sourceHash)
                                put("captureMode", when {
                                    captureAlns -> "ordinary-alns-iterations"
                                    captureDiagnostics -> "ordinary-clause-neighborhood"
                                    else -> "ordinary-finite-joint"
                                })
                                put("completeSearchAdmitted", (result as? SourceExecutionResult.Finite)
                                    ?.result?.completeSearchAdmitted)
                                put("stages", buildJsonObject {
                                    finite?.stats?.smt?.completeSearchStages.orEmpty()
                                        .forEach { (key, value) -> put(key, value) }
                                })
                                put("portfolio", NumericalResidualProfile.json(finite?.stats?.portfolio))
                            }
                            put("jointCaptureCounters", buildJsonObject {
                                finite?.stats?.smt?.completeSearchStages.orEmpty()
                                    .filterKeys { "Observation" in it }.forEach { (key, value) -> put(key, value) }
                            })
                            put("queryObserverNs", queryObserverNs)
                            put("guidanceObserverNs", guidanceObserverNs)
                            put("guidanceEvents", guidanceEvents)
                            put("captureNeighborhoods", captureDiagnostics && !captureAlns)
                            put("captureAlns", captureAlns)
                            if (captureDiagnostics) put("presolve", "off")
                            put("neighborhoodRecords", neighborhoodRecords)
                            put("neighborhoodObserverNs", neighborhoodObserverNs)
                            put("neighborhoodEncodedBytes", neighborhoodEncodedBytes)
                            put("neighborhoodEncodingOmitted", neighborhoodEncodingOmitted)
                            if (captureDiagnostics) {
                                put("profileJvmArgs", JsonArray(ManagementFactory.getRuntimeMXBean()
                                    .inputArguments.map(::JsonPrimitive)))
                                put("profileProcessors", Runtime.getRuntime().availableProcessors())
                                put("profileMaxHeapBytes", Runtime.getRuntime().maxMemory())
                                put("profileJavaVersion", System.getProperty("java.version"))
                                put("profileOsName", System.getProperty("os.name"))
                                put("profileOsArch", System.getProperty("os.arch"))
                                val holder = get(observed, "OnPortfolioNeighborhood")
                                val inventory = if (captureAlns) get(holder, "Iterations") else holder
                                put("neighborhoodCounters", buildJsonObject {
                                    val names = if (captureAlns) listOf("Calls", "Omitted", "Work", "ModeledBytes") else
                                        listOf("Calls", "Attempts", "CompletedEligible", "Omitted", "Work",
                                            "ModeledBytes", "Incomplete", "NoEligible", "SamplerDeclined")
                                    for (name in names) {
                                        put(name.replaceFirstChar { it.lowercaseChar() },
                                            get(inventory, name).toString())
                                    }
                                })
                                put("neighborhoodLimits", if (captureAlns) {
                                    "first32 admitted iterations across owners and runs; " +
                                        "4096 additional containing work and65536 modeled bytes"
                                } else {
                                    "first4 completed eligible calls; at most16 attempts; " +
                                        "4096 additional receiving work and65536 modeled bytes across the entire window"
                                })
                                put("neighborhoodScope", if (captureAlns) {
                                    "actual ordinary ALNS iterations on matched peer pools; " +
                                        "outer allowance, CP-node/LS-move callback deltas and repair elapsed time " +
                                        "are separate; partial callbacks do not measure all LP/source work; " +
                                        "endogenous choices are descriptive; observation may perturb " +
                                        "cancellation/scheduling; no performance claim"
                                } else {
                                    "bounded-size adjacency calls on matched peer pools; " +
                                        "exact drained snapshot and existing support pass; " +
                                        "copying may perturb containing cancellation and scheduling; " +
                                        "no performance claim"
                                })
                            }
                            put("captureGuidance", captureGuidance)
                            put("captureQueries", limit > 0)
                            put("guidanceObjectiveUnits", "native-minimized")
                            put("guidanceExactLocalBitsPerPart", 2048)
                            put("guidanceImportAndLocalLimitPerWorker", 16)
                            put("guidanceAttemptAndCommitLimitPerWorker", 128)
                            put("guidanceBranchWindow", 8)
                            put("guidancePreferenceScope",
                                "configured preference; no counterfactual ordering evaluated")
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
    }

    internal fun jointMetadata(worker: String, seed: Long, sourceHash: String, observation: Any): JsonObject =
        buildJsonObject {
            put("worker", worker)
            put("workerSeed", seed)
            put("coordinateSpace", "finite-companion-working-model")
            put("sourceSha256", sourceHash)
            for (name in listOf("Proposal", "Query", "Phase", "Disposition", "Operator", "SchedulingRound",
                "ProposalRound", "RequestedCount", "EffectiveCount", "CenterKind", "Cutoff",
                "IntegerCount", "RealCount", "BooleanCount")) {
                put(name.replaceFirstChar { it.lowercaseChar() }, call(observation, "get$name")?.toString())
            }
            for (name in listOf("Centers", "Bools", "Lower", "Upper")) {
                put(name.replaceFirstChar { it.lowercaseChar() }, buildJsonObject {
                    (get(observation, name) as Map<*, *>).forEach { (key, value) ->
                        put(key.toString(), value.toString())
                    }
                })
            }
            for (name in listOf("Freed", "RefutationRoots")) {
                put(name.replaceFirstChar { it.lowercaseChar() },
                    JsonArray((get(observation, name) as List<*>).map { JsonPrimitive(it as Int) }))
            }
            put("support", JsonArray((get(observation, "Support") as List<*>).map { bound ->
                val value = requireNotNull(bound)
                val coordinate = get(value, "Coordinate")
                buildJsonObject {
                    put("kind", get(coordinate, "Kind").toString())
                    put("id", get(coordinate, "Id") as Int)
                    put("upper", get(value, "Upper") as Boolean)
                    put("value", call(value, "getValue")?.toString())
                }
            }))
            put("sourceVerdictClaim", false)
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

    internal fun capture(query: Any, stop: ExecutionControl): ByteArray? {
        val model = get(query, "Model")
        if ((get(model, "M") as Int) > 4096 ||
            (get(model, "N") as Int).toLong() + (get(model, "M") as Int) > 8192) return null
        return call(query, "captureExactWithin", "finite-portfolio-query", 65_536, 1_048_576, 4096, stop) as ByteArray?
    }

    internal fun writeBasis(query: Any, file: File?): String? {
        if (file == null) return null
        val model = get(query, "Model")
        if ((get(model, "N") as Int).toLong() + (get(model, "M") as Int) > 8192) return null
        val text = (basisMetadata(call(query, "captureBasis")) ?: JsonNull).toString()
        file.writeText(text)
        return sha256(text.encodeToByteArray())
    }

    internal fun alnsMetadata(value: Any): JsonObject = buildJsonObject {
        for (name in listOf("Worker", "Seed", "Owner", "Run", "Iteration", "Bools", "Ints", "Reals",
            "Destroy", "Repair", "Fraction", "Incumbent", "Best", "Allowance", "Requested", "Effective",
            "ScopeComplete", "Repaired", "Accepted", "NewBest", "Reward", "CpNodes", "LsMoves", "RepairNanos",
            "Verdict", "Reason", "Endpoint")) {
            val field = call(value, "get$name")
            put(name.replaceFirstChar { it.lowercaseChar() }, when (field) {
                is Boolean -> JsonPrimitive(field)
                is Number -> JsonPrimitive(field)
                is String -> JsonPrimitive(field)
                else -> JsonNull
            })
        }
    }

    internal fun neighborhoodMetadata(worker: String, seed: Long, value: Any): JsonObject = buildJsonObject {
        put("worker", worker)
        put("workerSeed", seed)
        for (name in listOf("Call", "Bools", "Ints", "Requested", "Cursor", "Complete", "Truncated",
            "SupportPassComplete", "SamplerCompleted", "SelectedIndex", "Work", "ModeledBytes")) {
            val field = call(value, "get$name")
            put(name.replaceFirstChar { it.lowercaseChar() }, when (field) {
                is Boolean -> JsonPrimitive(field)
                is Number -> JsonPrimitive(field)
                else -> JsonNull
            })
        }
        val cursor = call(value, "getCursor") as Long?
        put("snapshotGeneration", cursor?.ushr(32))
        put("coordinateNamespace", "booleans first, then integer ids offset by bools")
        put("eligibilityCodes", "positive=support size,-1=payload shape,-2=Boolean namespace," +
            "-3=Boolean pinned/fixed,-4=integer namespace/atom,-5=integer pinned/fixed,-6=support size")
        val entries = call(value, "getEntries") as Array<*>?
        put("entries", buildJsonArray {
            entries?.forEachIndexed { index, raw ->
                if (raw == null) add(JsonNull) else {
                    val entry = raw as IntArray
                    add(buildJsonObject {
                        put("index", index)
                        put("origin", entry[0])
                        put("eligibility", entry[1])
                        put("support", buildJsonArray { for (i in 2 until entry.size) add(JsonPrimitive(entry[i])) })
                    })
                }
            }
        })
    }

    internal fun basisMetadata(basis: Any?): JsonObject? = basis?.let { value ->
        buildJsonObject {
            put("headings", JsonArray((get(value, "BasicVars") as IntArray).map(::JsonPrimitive)))
            put("statuses", JsonArray((get(value, "Status") as Array<*>).map { JsonPrimitive(it.toString()) }))
            put("captureEligible", get(value, "CaptureEligible") as Boolean)
        }
    }

    private fun get(owner: Any, name: String): Any = requireNotNull(call(owner, "get$name"))

    @Suppress("SpreadOperator")
    private fun call(owner: Any, name: String, vararg arguments: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *arguments)
}
