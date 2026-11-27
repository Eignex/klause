package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.lab.configureLabPaths
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolveEvidence
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CommittedCorpusIndexes
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.bounding.LpConfig
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException
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
        val file = CorpusFetcher.resolve(reference.source)
        val bytes = CorpusFiles.open(file).use { readSource(options, it) }
        options["source-archive-sha256"]?.let { expected ->
            val source = reference.source as? ProblemSource.External
            requireNotNull(source) { "archive identity requires an external indexed source" }
            val index = requireNotNull(CommittedCorpusIndexes.load(source.collection.id))
            val plain = CorpusFiles.plainPath(source.relPath)
            val entry = requireNotNull(index.entry(plain + CorpusFiles.SUFFIX) ?: index.entry(plain))
            require(entry.sha256 == expected) { "committed source index SHA-256 does not match the request" }
            val actual = SolveEvidence.fileHashes(mapOf("source" to file)).getValue("source")
            out.resolve("source-identity.json").writeText(buildJsonObject {
                put("indexedPath", entry.path)
                put("indexedBytes", entry.bytes)
                put("indexedSha256", entry.sha256)
                put("workerFile", file.name)
                put("workerBytes", file.length())
                put("workerSha256", actual)
                put("matchesIndexedBytes", actual == entry.sha256 && file.length() == entry.bytes)
                put("decodedSha256", options.getValue("sha256"))
                put("decodedBytes", bytes.size)
            }.toString())
        }
        out.resolve("source.mps").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        inspect(options, bytes, out)
    }

    internal fun sourceByteLimit(options: Map<String, String>): Int =
        if (options["capture-root-points"]?.toBooleanStrict() == true) 2_097_152 else 1_048_576

    internal fun readSource(options: Map<String, String>, source: InputStream): ByteArray =
        source.readNBytes(sourceByteLimit(options) + 1).also { validateSource(options, it) }

    internal fun validateSource(options: Map<String, String>, bytes: ByteArray) {
        require(bytes.size <= sourceByteLimit(options) && sha256(bytes) == options.getValue("sha256"))
    }

    internal fun parameters(options: Map<String, String>): List<String> {
        val required = setOf("suite", "problem", "sha256", "seed", "handoff")
        require(options.keys.containsAll(required) && (options.keys - required).all {
            it in setOf("timeout-ms", "captures", "cutoff-bits", "capture-worker", "capture-route", "capture-guidance",
                "portfolio-policy", "portfolio-progress", "capture-mode", "inspect-rays",
                "source-refutation-center-step", "capture-root-points", "lp-ceiling", "automatic-sparse",
                "source-archive-sha256")
        })
        val mode = options["capture-mode"] ?: "portfolio"
        require(mode in setOf("portfolio", "joint-before", "joint-ordinary", "joint-handoff"))
        val captureRootPoints = options["capture-root-points"]?.toBooleanStrict() ?: false
        options["source-archive-sha256"]?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
        if ("lp-ceiling" in options) require(captureRootPoints && options["lp-ceiling"] == "default") {
            "lp-ceiling=default requires ordinary root point capture"
        }
        options["automatic-sparse"]?.let {
            require(captureRootPoints && options["lp-ceiling"] == "default")
            it.toBooleanStrict()
        }
        val captureJoint = mode == "joint-ordinary" || mode == "joint-handoff"
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
        if (captureRootPoints) {
            require(mode == "portfolio" && preset == "ordinary" && !handoff && !progress &&
                options["captures"] == "0") {
                "root point capture requires ordinary portfolio, handoff=false, portfolio-progress=false and captures=0"
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

    internal fun sourcePolicy(options: Map<String, String>): SourceExecutionPolicy =
        SourceExecutionPolicy(lpCeiling = options["lp-ceiling"]?.let {
            val automatic = options["automatic-sparse"]?.toBooleanStrict() == true
            LpConfig.parse(if (automatic) "$it,+automatic-sparse" else it)
        })

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
        val captureRootPoints = options["capture-root-points"]?.toBooleanStrict() ?: false
        val seed = options.getValue("seed").toLong()
        val cutoffBits = options["cutoff-bits"]?.toLong()
        val captureWorker = options["capture-worker"] ?: "bt/lp-default"
        val captureRoute = options["capture-route"] ?: "any"
        require(captureWorker in setOf("bt/lp-default", "any"))
        require(captureRoute in setOf("any", "SESSION", "SCOPED_FLOAT", "SCOPED_CONTINUATION"))
        require(timeout in 1L..30_000L && limit in 0..4)
        validateSource(options, bytes)
        out.mkdirs()
        out.resolve("source.mps").writeBytes(bytes)
        val sourceHash = sha256(bytes)
        val executionStarted = System.nanoTime()
        val stop = ExecutionControl.after(timeout.milliseconds)
        val original = requireNotNull(Mps.parse(bytes.decodeToString()).toLinearSourceProblem(cancellation = stop))
        val shapeLimit = if (captureRootPoints) 6000 else 4096
        require(original.variables.size <= shapeLimit && original.rows.size <= shapeLimit)
        val settings = ProblemSettings()
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(original, settings),
            ExecutionOperation.OPTIMIZE, FiniteEngine.MIXED,
            maximize = original.objective?.sense == ObjectiveSense.MAXIMIZE, cancellation = stop))
        if (SourceSetup.executionKind(invocation) != SourceExecutionKind.FINITE) {
            out.resolve("result.json").writeText(buildJsonObject {
                put("captureDeclined", "finite source route unavailable after preparation")
                put("sourceInputByteLimit", sourceByteLimit(options))
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
        val policy = sourcePolicy(options)
        val window = QueryWindow(captureWorker, limit)
        val rootPoints = if (captureRootPoints) RootPointWindow(captureWorker) else null
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
                            finiteRequest = { shape ->
                                policy.finiteRequest(shape, FiniteEngine.MIXED,
                                    cancellation = stop, randomSeed = seed,
                                    onPortfolioEvent = { worker, event ->
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
                        if (rootPoints != null) {
                            val rootObserver: (String, Any) -> Unit = { worker, event ->
                                rootPoints.observe(worker, event, System.nanoTime() - executionStarted)
                            }
                            val attach = observed.javaClass.methods.single {
                                it.name.startsWith("withPortfolioRootPointObserver") && it.parameterCount == 1
                            }
                            observed = attach.invoke(observed, rootObserver) as SourceExecutionRequest
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
                        val rootExecutionNs = rootPoints?.let { System.nanoTime() - executionStarted }
                        val finite = (result as? SourceExecutionResult.Finite)?.result?.outcome
                            as? FiniteSolveOutcome.Completed
                        val rootLibraryFailures = finite?.stats?.lp?.phases
                            ?.get("ROOT_POINT_OBSERVATION_ROOT")?.outcomes?.get("FAILED")
                            ?: finite?.let { 0L }
                        val rootSerializationNs = rootPoints?.let { capture ->
                            val started = System.nanoTime()
                            val snapshot = capture.snapshot(rootLibraryFailures).json()
                            val encoded = JsonObject(snapshot +
                                ("sourceSha256" to JsonPrimitive(sourceHash))).toString().encodeToByteArray()
                            require(encoded.size <= ROOT_POINT_JOURNAL_BYTE_LIMIT)
                            out.resolve("root-points.json").writeBytes(encoded)
                            System.nanoTime() - started
                        } ?: 0L
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
                            if (captureJoint) {
                                put("sourceSha256", sourceHash)
                                put("captureMode", "ordinary-finite-joint")
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
                            put("captureGuidance", captureGuidance)
                            put("captureRootPoints", captureRootPoints)
                            if (captureRootPoints) {
                                put("requestedLpCeiling", options["lp-ceiling"])
                                put("indexedSourceSha256", options["source-archive-sha256"])
                                put("automaticSparseTreatment", options["automatic-sparse"]?.toBooleanStrict() == true)
                                put("resolvedPolicyLpCeiling", policy.lpCeiling?.emphasis?.name ?: "AGGRESSIVE")
                                put("sourceSetupSettings", "DEFAULT_PROBLEM_SETTINGS_NO_SEMANTIC_OVERRIDES")
                                put("sourceSetupLpMaxTableauCells", settings.lpMaxTableauCells)
                                put("sourceSetupLpCeilingTableauCells", settings.lpCeilingTableauCells)
                                put("admissionSchema", 1)
                                put("rootPointRecordByteLimit", ROOT_POINT_RECORD_BYTE_LIMIT)
                                put("rootPointJournalByteLimit", ROOT_POINT_JOURNAL_BYTE_LIMIT)
                            }
                            put("sourceInputByteLimit", sourceByteLimit(options))
                            put("rootPointSerializationNs", rootSerializationNs)
                            put("rootPointSourceShapeLimit", shapeLimit)
                            put("rootPointScope", "model-facing lifecycle; numerical cursor lineage UNKNOWN")
                            put("captureQueries", limit > 0)
                            put("guidanceObjectiveUnits", "native-minimized")
                            put("guidanceExactLocalBitsPerPart", 2048)
                            put("guidanceImportAndLocalLimitPerWorker", 16)
                            put("guidanceAttemptAndCommitLimitPerWorker", 128)
                            put("guidanceBranchWindow", 8)
                            put("guidancePreferenceScope",
                                "configured preference; no counterfactual ordering evaluated")
                            put("guidanceCutoffScope", "last scalar read; exact cutoff not captured")
                            put("executionNs", rootExecutionNs ?: (System.nanoTime() - executionStarted))
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

    internal data class RootPointLimits(
        val owners: Int = 64,
        val ownerEvents: Int = 512,
        val events: Int = 8192,
        val sources: Int = 64,
        val objectives: Int = 128,
        val models: Int = 512,
        val points: Int = 1024,
    ) {
        init {
            require(owners in 1..64 && ownerEvents in 1..512 && events in 1..8192 &&
                sources in 1..64 && objectives in 1..128 && models in 1..512 && points in 1..1024)
        }
    }

    internal const val ROOT_POINT_RECORD_BYTE_LIMIT = 8192
    internal const val ROOT_POINT_JOURNAL_BYTE_LIMIT = 67_371_008

    private class UnsupportedAdmissionPayload : IllegalArgumentException()

    internal data class RootAdmissionRecord(
        val schema: Int,
        val disposition: String,
        val emphasis: String?,
        val sparseOverride: Boolean?,
        val sparsePermitted: Boolean?,
        val automaticEligible: Boolean?,
        val automaticEvaluated: Long,
        val automaticPassed: Long,
        val outerEvaluated: Long,
        val outerPassed: Long,
        val estimatedRows: Long?,
        val estimatedColumns: Long?,
        val estimatedCells: Long?,
        val baseCellCap: Long?,
        val ceilingCellCap: Long?,
        val boundingPermitted: Boolean?,
        val withinCeiling: Boolean?,
        val structurallyApplicable: Boolean?,
        val projectionAllowed: Boolean?,
        val producerQueryWork: Long?,
        val producerPreparationWork: Long?,
        val helperQueryWork: Long?,
        val helperPreparationWork: Long?,
        val resolvedBounding: Boolean?,
        val resolvedProbe: Boolean?,
        val resolvedBranching: Boolean?,
        val resolvedComponentOwnership: Boolean?,
        val resolvedLbTreeSearch: Boolean?,
        val resolvedRootMaxWork: Long?,
        val resolvedBoundMaxDepth: Int?,
        val resolvedRootBudgetMillis: Long?,
        val resolvedRootBudgetFraction: Double?,
        val resolvedLpWorkBudgetFraction: Double?,
        val metrics: Map<String, Long>,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("schema", schema)
            put("disposition", disposition)
            put("emphasis", emphasis)
            put("sparseOverride", sparseOverride)
            put("sparsePermitted", sparsePermitted)
            put("automaticEligible", automaticEligible)
            put("automaticEvaluated", automaticEvaluated)
            put("automaticPassed", automaticPassed)
            put("outerEvaluated", outerEvaluated)
            put("outerPassed", outerPassed)
            put("estimatedRows", estimatedRows)
            put("estimatedColumns", estimatedColumns)
            put("estimatedCells", estimatedCells)
            put("baseCellCap", baseCellCap)
            put("ceilingCellCap", ceilingCellCap)
            put("boundingPermitted", boundingPermitted)
            put("withinCeiling", withinCeiling)
            put("structurallyApplicable", structurallyApplicable)
            put("projectionAllowed", projectionAllowed)
            put("producerQueryWork", producerQueryWork)
            put("producerPreparationWork", producerPreparationWork)
            put("helperQueryWork", helperQueryWork)
            put("helperPreparationWork", helperPreparationWork)
            put("resolvedBounding", resolvedBounding)
            put("resolvedProbe", resolvedProbe)
            put("resolvedBranching", resolvedBranching)
            put("resolvedComponentOwnership", resolvedComponentOwnership)
            put("resolvedLbTreeSearch", resolvedLbTreeSearch)
            put("resolvedRootMaxWork", resolvedRootMaxWork)
            put("resolvedBoundMaxDepth", resolvedBoundMaxDepth)
            put("resolvedRootBudgetMillis", resolvedRootBudgetMillis)
            put("resolvedRootBudgetFraction", resolvedRootBudgetFraction)
            put("resolvedLpWorkBudgetFraction", resolvedLpWorkBudgetFraction)
            put("planScope", "RESOLVED_DECISION")
            put("unevaluatedMeaning", "UNKNOWN")
            put("metrics", buildJsonObject { metrics.forEach { (key, value) -> put(key, value) } })
        }

        private fun validate(eventDisposition: String, eventAutomatic: Boolean) {
            require(schema == 1 && disposition in dispositions && disposition == eventDisposition)
            require(emphasis == null || emphasis in setOf("OFF", "CONSERVATIVE", "DEFAULT", "AGGRESSIVE"))
            require(listOf(estimatedRows, estimatedColumns, estimatedCells, producerQueryWork,
                producerPreparationWork, helperQueryWork, helperPreparationWork).all { it == null || it >= 0L })
            if (listOf(resolvedRootBudgetFraction, resolvedLpWorkBudgetFraction).any {
                it != null && !it.isFinite()
            }) throw UnsupportedAdmissionPayload()
            require(metrics.values.all { it >= 0L })
            if (disposition == "NOT_CONFIGURED") {
                require(listOf(emphasis, sparseOverride, sparsePermitted, automaticEligible, estimatedRows, estimatedColumns, estimatedCells, baseCellCap, ceilingCellCap, boundingPermitted, withinCeiling, structurallyApplicable, projectionAllowed, producerQueryWork, producerPreparationWork, helperQueryWork, helperPreparationWork, resolvedBounding, resolvedProbe, resolvedBranching, resolvedComponentOwnership, resolvedLbTreeSearch, resolvedRootMaxWork, resolvedBoundMaxDepth, resolvedRootBudgetMillis, resolvedRootBudgetFraction, resolvedLpWorkBudgetFraction).all { it == null })
                require(automaticEvaluated == 0L && automaticPassed == 0L &&
                    outerEvaluated == 0L && outerPassed == 0L && metrics.isEmpty() && !eventAutomatic)
                return
            }
            require(listOf(emphasis, sparsePermitted, automaticEligible, estimatedRows, estimatedColumns, estimatedCells, baseCellCap, ceilingCellCap, boundingPermitted, withinCeiling, structurallyApplicable, projectionAllowed, producerQueryWork, producerPreparationWork, resolvedBounding, resolvedProbe, resolvedBranching, resolvedComponentOwnership, resolvedLbTreeSearch, resolvedRootMaxWork, resolvedBoundMaxDepth, resolvedRootBudgetMillis, resolvedRootBudgetFraction, resolvedLpWorkBudgetFraction).all { it != null })
            val automatic = completedGuard(automaticEvaluated, automaticPassed, 15)
            val considered = completedGuard(outerEvaluated, outerPassed, 6)
            require(automaticEligible == automatic)
            require((automaticPassed and 1L != 0L) == (sparseOverride != true))
            if (automaticEvaluated and 2L != 0L) require((automaticPassed and 2L != 0L) == sparsePermitted)
            if (outerEvaluated and 2L != 0L) require((outerPassed and 2L != 0L) == sparsePermitted)
            if (outerEvaluated and 8L != 0L) require((outerPassed and 8L != 0L) == (sparseOverride == true || automatic))
            if (outerEvaluated and 16L != 0L) require((outerPassed and 16L != 0L) == structurallyApplicable)
            if (outerEvaluated and 32L != 0L) require((outerPassed and 32L != 0L) == boundingPermitted)
            val helperReturned = disposition == "ATTEMPTED_DECLINED" || disposition == "ADMITTED"
            require(considered == (disposition != "NOT_CONSIDERED"))
            require((helperPreparationWork != null && helperQueryWork != null) == helperReturned)
            require((helperPreparationWork == null && helperQueryWork == null) == !helperReturned)
            if (helperReturned) {
                val preparation = requireNotNull(helperPreparationWork)
                val query = requireNotNull(helperQueryWork)
                require(preparation <= requireNotNull(producerPreparationWork))
                require(query <= requireNotNull(producerQueryWork))
                metrics["QueryWorkAllowance"]?.let { require(it == query) }
            }
            if (!considered) require(metrics.isEmpty())
            else {
                require(projectionAllowed == (disposition != "PROJECTION_SKIPPED"))
                require(metrics["Automatic"] == if (automatic) 1L else 0L)
            }
            when (disposition) {
                "PROJECTION_SKIPPED" -> require(metrics == mapOf("DeclineProjection" to 1L,
                    "Automatic" to if (automatic) 1L else 0L))
                "ATTEMPTED_DECLINED" -> require(metrics["Admitted"] != 1L &&
                    metrics.filterKeys { it.startsWith("Decline") }.values.toList() == listOf(1L))
                "ADMITTED" -> require(metrics["Admitted"] == 1L && metrics.keys.none { it.startsWith("Decline") })
            }
            require(eventAutomatic == (disposition == "ADMITTED" && sparseOverride != true))
        }

        companion object {
            private val dispositions = setOf("NOT_CONFIGURED", "NOT_CONSIDERED", "PROJECTION_SKIPPED",
                "ATTEMPTED_DECLINED", "ADMITTED")
            private val getterNames = setOf(
                "getSchema",
                "getDisposition",
                "getEmphasis",
                "getSparseOverride",
                "getSparsePermitted",
                "getAutomaticEligible",
                "getAutomaticEvaluated",
                "getAutomaticPassed",
                "getOuterEvaluated",
                "getOuterPassed",
                "getEstimatedRows",
                "getEstimatedColumns",
                "getEstimatedCells",
                "getBaseCellCap",
                "getCeilingCellCap",
                "getBoundingPermitted",
                "getWithinCeiling",
                "getStructurallyApplicable",
                "getProjectionAllowed",
                "getProducerQueryWork",
                "getProducerPreparationWork",
                "getHelperQueryWork",
                "getHelperPreparationWork",
                "getResolvedBounding",
                "getResolvedProbe",
                "getResolvedBranching",
                "getResolvedComponentOwnership",
                "getResolvedLbTreeSearch",
                "getResolvedRootMaxWork",
                "getResolvedBoundMaxDepth",
                "getResolvedRootBudgetMillis",
                "getResolvedRootBudgetFraction",
                "getResolvedLpWorkBudgetFraction",
                "getMetrics",
            )
            private val metricNames = listOf(
                "Rows",
                "Automatic",
                "Columns",
                "Nonzeros",
                "Components",
                "LargestComponentRows",
                "LargestComponentColumns",
                "ScalarBits",
                "RowIntersectionRisk",
                "FillRiskLimit",
                "EstimatedMonolithicQueryWork",
                "EstimatedFactorAttemptWork",
                "FactorAttemptForecast",
                "EstimatedProbeWork",
                "EstimatedProbeTotalWork",
                "QueryWorkAllowance",
                "FactorEntryLimit",
                "FactorWorkLimit",
                "ReservedBytes",
                "CheckingReservedBytes",
                "PreparationWork",
                "WorkLimit",
                "StorageLimit",
                "Admitted",
                "SourceInspectedFillRisk",
                "SourceEstimatedPassWork",
                "SourceEstimatedPassTotalWork",
                "DeclineProjection",
                "DeclineShape",
                "DeclineCancelled",
                "DeclineQueryWork",
                "DeclinePreparationWork",
                "DeclineStrictRow",
                "DeclineSource",
                "DeclineFillRisk",
                "DeclineStorage",
                "DeclineScalarWidth",
                "DeclineEntries",
                "DeclineFactorForecast",
            )

            private fun completedGuard(evaluated: Long, passed: Long, count: Int): Boolean {
                val full = (1L shl count) - 1L
                require(evaluated in 1L..full && (evaluated and (evaluated + 1L)) == 0L)
                require((passed and evaluated.inv()) == 0L)
                val complete = evaluated == full && passed == full
                require(complete || passed == (evaluated ushr 1))
                return complete
            }

            fun read(value: Any, eventDisposition: String, eventAutomatic: Boolean,
                read: (Any, String) -> Any?): RootAdmissionRecord {
                val getters = value.javaClass.methods.filter {
                    it.name.startsWith("get") && it.name != "getClass" && it.parameterCount == 0
                }.map { it.name }.toSet()
                if (getters != getterNames || read(value, "getSchema") != 1) throw UnsupportedAdmissionPayload()
                val borrowed = read(value, "getMetrics") as Map<*, *>
                if (borrowed.size > metricNames.size || borrowed.keys.any { it !in metricNames }) {
                    throw UnsupportedAdmissionPayload()
                }
                val metrics = LinkedHashMap<String, Long>()
                metricNames.forEach { key ->
                    if (borrowed.containsKey(key)) metrics[key] = borrowed[key] as Long
                }
                val result = RootAdmissionRecord(
                    schema = read(value, "getSchema") as Int,
                    disposition = requireNotNull(read(value, "getDisposition")).toString(),
                    emphasis = read(value, "getEmphasis")?.toString(),
                    sparseOverride = read(value, "getSparseOverride") as Boolean?,
                    sparsePermitted = read(value, "getSparsePermitted") as Boolean?,
                    automaticEligible = read(value, "getAutomaticEligible") as Boolean?,
                    automaticEvaluated = read(value, "getAutomaticEvaluated") as Long,
                    automaticPassed = read(value, "getAutomaticPassed") as Long,
                    outerEvaluated = read(value, "getOuterEvaluated") as Long,
                    outerPassed = read(value, "getOuterPassed") as Long,
                    estimatedRows = read(value, "getEstimatedRows") as Long?,
                    estimatedColumns = read(value, "getEstimatedColumns") as Long?,
                    estimatedCells = read(value, "getEstimatedCells") as Long?,
                    baseCellCap = read(value, "getBaseCellCap") as Long?,
                    ceilingCellCap = read(value, "getCeilingCellCap") as Long?,
                    boundingPermitted = read(value, "getBoundingPermitted") as Boolean?,
                    withinCeiling = read(value, "getWithinCeiling") as Boolean?,
                    structurallyApplicable = read(value, "getStructurallyApplicable") as Boolean?,
                    projectionAllowed = read(value, "getProjectionAllowed") as Boolean?,
                    producerQueryWork = read(value, "getProducerQueryWork") as Long?,
                    producerPreparationWork = read(value, "getProducerPreparationWork") as Long?,
                    helperQueryWork = read(value, "getHelperQueryWork") as Long?,
                    helperPreparationWork = read(value, "getHelperPreparationWork") as Long?,
                    resolvedBounding = read(value, "getResolvedBounding") as Boolean?,
                    resolvedProbe = read(value, "getResolvedProbe") as Boolean?,
                    resolvedBranching = read(value, "getResolvedBranching") as Boolean?,
                    resolvedComponentOwnership = read(value, "getResolvedComponentOwnership") as Boolean?,
                    resolvedLbTreeSearch = read(value, "getResolvedLbTreeSearch") as Boolean?,
                    resolvedRootMaxWork = read(value, "getResolvedRootMaxWork") as Long?,
                    resolvedBoundMaxDepth = read(value, "getResolvedBoundMaxDepth") as Int?,
                    resolvedRootBudgetMillis = read(value, "getResolvedRootBudgetMillis") as Long?,
                    resolvedRootBudgetFraction = read(value, "getResolvedRootBudgetFraction") as Double?,
                    resolvedLpWorkBudgetFraction = read(value, "getResolvedLpWorkBudgetFraction") as Double?,
                    metrics = metrics.toMap(),
                )
                result.validate(eventDisposition, eventAutomatic)
                return result
            }
        }
    }

    internal data class RootPointValueCommit(
        val variable: Int,
        val preferred: Long,
        val limit: Long,
        val atMost: Boolean,
        val level: Int,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("variable", variable)
            put("preferred", preferred)
            put("limit", limit)
            put("atMost", atMost)
            put("level", level)
        }
    }

    internal data class RootPointRecord(
        val worker: String,
        val owner: Int,
        val source: Int?,
        val objective: Int?,
        val pointObjective: Int?,
        val model: Int?,
        val point: Int?,
        val previousPoint: Int?,
        val sequence: Long,
        val queryCall: Long,
        val stage: String,
        val disposition: String,
        val automatic: Boolean,
        val auxiliary: Boolean,
        val partial: Boolean,
        val sourceRoot: Boolean?,
        val paused: Boolean?,
        val termination: String?,
        val elapsedNs: Long,
        val admission: RootAdmissionRecord? = null,
        val valueCommit: RootPointValueCommit? = null,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("worker", worker)
            put("owner", owner)
            put("source", source)
            put("objective", objective)
            put("pointObjective", pointObjective)
            put("model", model)
            put("point", point)
            put("previousPoint", previousPoint)
            put("sequence", sequence)
            put("queryCall", queryCall)
            put("stage", stage)
            put("disposition", disposition)
            put("automatic", automatic)
            put("auxiliary", auxiliary)
            put("partial", partial)
            put("sourceRoot", sourceRoot)
            put("paused", paused)
            put("termination", termination)
            put("elapsedNs", elapsedNs)
            admission?.let { put("admission", it.json()) }
            valueCommit?.let { put("valueCommit", it.json()) }
        }.also { require(it.toString().encodeToByteArray().size <= ROOT_POINT_RECORD_BYTE_LIMIT) }
    }

    internal data class RootPointOwnerSnapshot(
        val owner: Int,
        val worker: String,
        val seen: Long,
        val dropped: Long,
        val retainedOwnerObserved: Boolean,
        val closeBegin: Boolean,
        val closeEnd: Boolean,
        val openQuery: Long?,
        val coverage: String,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("owner", owner)
            put("worker", worker)
            put("seen", seen)
            put("dropped", dropped)
            put("retainedOwnerObserved", retainedOwnerObserved)
            put("closeBegin", closeBegin)
            put("closeEnd", closeEnd)
            put("openQuery", openQuery)
            put("coverage", coverage)
        }
    }

    internal data class RootPointSnapshot(
        val selectedWorker: String,
        val seen: Long,
        val unselected: Long,
        val dropped: Long,
        val failures: Long,
        val libraryFailures: Long?,
        val observerNs: Long,
        val identityOverflow: Long,
        val limits: RootPointLimits,
        val droppedStages: Map<String, Long>,
        val owners: List<RootPointOwnerSnapshot>,
        val events: List<RootPointRecord>,
        val admissionFailures: Long = 0L,
        val admissionUnsupported: Long = 0L,
    ) {
        val partialQueryCoverage: Boolean get() = true
        val absenceEvidence: Boolean get() = false

        fun json(): JsonObject = buildJsonObject {
            put("selectedWorker", selectedWorker)
            put("seen", seen)
            put("unselected", unselected)
            put("retained", events.size)
            put("dropped", dropped)
            put("failures", failures)
            put("libraryFailures", libraryFailures)
            put("admissionFailures", admissionFailures)
            put("admissionUnsupported", admissionUnsupported)
            put("admissionCoverage", "RECORDED_DECISIONS_ONLY")
            put("missingAdmissionMeaning", "UNKNOWN")
            put("rootPointRecordByteLimit", ROOT_POINT_RECORD_BYTE_LIMIT)
            put("rootPointJournalByteLimit", ROOT_POINT_JOURNAL_BYTE_LIMIT)
            put("observerNs", observerNs)
            put("identityOverflow", identityOverflow)
            put("ownerLimit", limits.owners)
            put("ownerEventLimit", limits.ownerEvents)
            put("eventLimit", limits.events)
            put("sourceIdentityLimit", limits.sources)
            put("objectiveIdentityLimit", limits.objectives)
            put("modelIdentityLimit", limits.models)
            put("pointIdentityLimit", limits.points)
            put("cursorLineage", "UNKNOWN")
            put("partialQueryCoverage", partialQueryCoverage)
            put("absenceEvidence", absenceEvidence)
            put("journalScope", "instrumented model-facing root/primal calls and guidance/target transitions")
            put("identityScope", "private engine Problem/objective references; original source hash separate")
            put("emptyOwnerCoverage", "UNKNOWN")
            put("observerCostScope", "synchronous callback subset; dispatch/allocation overhead unmeasured")
            put("droppedStages", buildJsonObject {
                droppedStages.forEach { (stage, count) -> put(stage, count) }
            })
            put("owners", JsonArray(owners.map { it.json() }))
            put("events", JsonArray(events.map { it.json() }))
        }
    }

    internal class RootPointWindow(private val selectedWorker: String,
        private val limits: RootPointLimits = RootPointLimits()) {
        init { require(selectedWorker.length <= 128) }

        private class WeakIds(private val limit: Int) {
            private val values = ArrayList<WeakReference<Any>>()
            var overflow = 0L
                private set

            fun id(value: Any?): Int? {
                if (value == null) return null
                val known = values.indexOfFirst { it.get() === value }
                if (known >= 0) return known
                if (values.size == limit) {
                    overflow++
                    return null
                }
                values.add(WeakReference(value))
                return values.lastIndex
            }
        }

        private class Owner(val worker: String) {
            var sequence = 0L
            var seen = 0L
            var dropped = 0L
            var retainedOwnerObserved = false
            var identitySet = false
            var source: Int? = null
            var objective: Int? = null
            var lastQuery = 0L
            var openQuery: Long? = null
            var openModel: Int? = null
            var closeBegin = false
            var closeEnd = false
            var incomplete = false
        }

        private val lock = Any()
        private val ownerIds = WeakIds(limits.owners)
        private val sourceIds = WeakIds(limits.sources)
        private val objectiveIds = WeakIds(limits.objectives)
        private val modelIds = WeakIds(limits.models)
        private val pointIds = WeakIds(limits.points)
        private val owners = ArrayList<Owner>()
        private val records = ArrayList<RootPointRecord>()
        private val droppedStages = LinkedHashMap<String, Long>()
        private var seen = 0L
        private var unselected = 0L
        private var dropped = 0L
        private var failures = 0L
        private var admissionFailures = 0L
        private var admissionUnsupported = 0L
        private var observerNs = 0L

        fun observe(worker: String, event: Any, elapsedNs: Long) {
            val started = System.nanoTime()
            synchronized(lock) {
                var admissionEvent = false
                try {
                    seen++
                    if (selectedWorker != "any" && selectedWorker != worker) {
                        unselected++
                        return@synchronized
                    }
                    require(worker.length <= 128)
                    val index = ownerIds.id(get(event, "Owner"))
                    if (index == null) {
                        dropped++
                        return@synchronized
                    }
                    if (index == owners.size) owners.add(Owner(worker))
                    val owner = owners[index]
                    owner.seen++
                    val sequence = get(event, "Sequence") as Long
                    val stage = get(event, "Stage").toString()
                    admissionEvent = stage == "ADMISSION"
                    val disposition = get(event, "Disposition").toString()
                    require(stage.length <= 64 && disposition.length <= 64)
                    require(stage in setOf("ADMISSION", "OWNER", "QUERY_CALL", "COPY", "GUIDANCE", "TARGETS",
                        "HANDOFF", "CONSUMER", "CLOSE_BEGIN", "CLOSE_END"))
                    if (owner.worker != worker || owner.closeEnd || sequence != owner.sequence + 1L ||
                        (owner.closeBegin && stage != "CLOSE_END")
                    ) owner.incomplete = true
                    owner.sequence = sequence
                    if (owner.seen > limits.ownerEvents || records.size == limits.events) {
                        owner.incomplete = true
                        owner.dropped++
                        dropped++
                        droppedStages[stage] = (droppedStages[stage] ?: 0L) + 1L
                        return@synchronized
                    }
                    val overflowBefore = identityOverflow()
                    val source = sourceIds.id(get(event, "Source"))
                    val objective = objectiveIds.id(get(event, "Objective"))
                    val pointObjective = objectiveIds.id(call(event, "getPointObjective"))
                    val model = modelIds.id(call(event, "getModel"))
                    val point = pointIds.id(call(event, "getPoint"))
                    val previous = pointIds.id(call(event, "getPreviousPoint"))
                    if (identityOverflow() != overflowBefore) owner.incomplete = true
                    if (owner.identitySet && (owner.source != source || owner.objective != objective)) {
                        owner.incomplete = true
                    }
                    owner.identitySet = true
                    owner.source = source
                    owner.objective = objective
                    val query = get(event, "Query") as Long
                    if (query < 0L || sequence <= 0L) owner.incomplete = true
                    if (stage == "OWNER" && disposition == "RETAINED") owner.retainedOwnerObserved = true
                    if (stage == "QUERY_CALL" && disposition != "ENTERED" &&
                        disposition != "OPTIMAL" && disposition != "NON_OPTIMAL" && disposition != "NO_RESULT" &&
                        disposition != "ROOT_WORK" && disposition != "CANCELLED"
                    ) owner.incomplete = true
                    if (stage == "QUERY_CALL" && (disposition == "ROOT_WORK" || disposition == "CANCELLED") &&
                        owner.openQuery != null
                    ) owner.incomplete = true
                    if (stage == "QUERY_CALL" && disposition == "ENTERED") {
                        if (owner.openQuery != null || query != owner.lastQuery + 1L || model == null) {
                            owner.incomplete = true
                        }
                        owner.lastQuery = query
                        owner.openQuery = query
                        owner.openModel = model
                    } else {
                        if (query != owner.lastQuery) owner.incomplete = true
                        if (stage == "QUERY_CALL" && (disposition == "OPTIMAL" ||
                                disposition == "NON_OPTIMAL" || disposition == "NO_RESULT")
                        ) {
                            if (owner.openQuery != query || owner.openModel != model || model == null) {
                                owner.incomplete = true
                            }
                            owner.openQuery = null
                            owner.openModel = null
                        }
                    }
                    if (stage == "CLOSE_BEGIN") {
                        if (owner.closeBegin || disposition != "BEGIN" || owner.openQuery != null) {
                            owner.incomplete = true
                        }
                        owner.closeBegin = true
                    }
                    if (stage == "CLOSE_END") {
                        if (!owner.closeBegin || disposition != "END" || owner.openQuery != null) {
                            owner.incomplete = true
                        }
                        owner.closeEnd = true
                    }
                    val automatic = get(event, "Automatic") as Boolean
                    val auxiliary = get(event, "Auxiliary") as Boolean
                    val partial = get(event, "Partial") as Boolean
                    val sourceRoot = call(event, "getSourceRoot") as Boolean?
                    val paused = call(event, "getPaused") as Boolean?
                    val termination = call(event, "getTermination")?.toString()?.take(64)
                    val admission = if (stage == "ADMISSION") {
                        require(model == null && point == null && previous == null && pointObjective == null &&
                            !auxiliary && !partial && sourceRoot == null && paused == null && termination == null)
                        RootAdmissionRecord.read(get(event, "Admission"), disposition, automatic, ::call)
                    } else null
                    val valueCommit = if (stage == "CONSUMER" && disposition == "VALUE_COMMITTED") {
                        require(automatic && !auxiliary && !partial && sourceRoot == true && point != null)
                        val commit = get(event, "ValueCommit")
                        RootPointValueCommit(get(commit, "Variable") as Int, get(commit, "Preferred") as Long,
                            get(commit, "Limit") as Long, get(commit, "AtMost") as Boolean,
                            get(commit, "Level") as Int).also {
                            require(it.variable >= 0 && it.level > 0)
                            require(if (it.atMost) it.preferred <= it.limit else it.preferred >= it.limit)
                        }
                    } else null
                    records.add(RootPointRecord(worker, index, source, objective, pointObjective, model,
                        point, previous, sequence, query, stage, disposition,
                        automatic, auxiliary, partial, sourceRoot, paused, termination,
                        elapsedNs, admission, valueCommit))
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    failures++
                    if (admissionEvent) admissionFailures++
                    if (failure is UnsupportedAdmissionPayload) admissionUnsupported++
                } finally {
                    observerNs += System.nanoTime() - started
                }
            }
        }

        private fun identityOverflow(): Long = ownerIds.overflow + sourceIds.overflow +
            objectiveIds.overflow + modelIds.overflow + pointIds.overflow

        fun snapshot(libraryFailures: Long? = 0L): RootPointSnapshot = synchronized(lock) {
            RootPointSnapshot(selectedWorker, seen, unselected, dropped, failures, libraryFailures,
                observerNs, identityOverflow(), limits, droppedStages.toMap(),
                owners.mapIndexed { index, owner ->
                    RootPointOwnerSnapshot(index, owner.worker, owner.seen, owner.dropped,
                        owner.retainedOwnerObserved, owner.closeBegin, owner.closeEnd, owner.openQuery,
                        if (owner.closeBegin && owner.closeEnd && owner.openQuery == null && !owner.incomplete &&
                            failures == 0L && libraryFailures == 0L && ownerIds.overflow == 0L
                        ) "CLOSED_JOURNALED_SCOPE" else "UNKNOWN")
                }, records.toList(), admissionFailures, admissionUnsupported)
        }

        private fun get(event: Any, name: String): Any = requireNotNull(call(event, "get$name"))

        private fun call(event: Any, name: String): Any? = try {
            event.javaClass.getMethod(name).invoke(event)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
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
