package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.condition
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.current
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.field
import com.eignex.klause.bench.profile.NumericalFrontierSnapshot.Companion.stats
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.LinearTraversal
import com.eignex.klause.solver.pipeline.NumericalLinearRequest
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceProblemRoute
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MpsProofFrontier {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (System.getenv("KLAUSE_FRONTIER_MEASURED_JVM") != "1") {
            if (args.size == 1 && args[0].startsWith("batch64=")) {
                val request = Base64.getDecoder().decode(args[0].substringAfter('='))
                require(request.size <= 64 * 1024)
                val runs = Json.decodeFromString<List<List<String>>>(request.decodeToString())
                require(runs.size in 1..64)
                // Each run gets a fresh measured JVM, so earlier runs leave no compiled or cached state behind.
                runs.forEachIndexed { index, run ->
                    val directory = out.resolve("$index").apply { mkdirs() }
                    runCatching { launch(run.toTypedArray(), directory) }.onFailure {
                        directory.resolve("launch-failure.txt").writeText("${it.message}\n")
                    }
                }
            } else launch(args, out)
            return
        }
        require(Runtime.getRuntime().availableProcessors() == 1)
        if (args.contentEquals(arrayOf("activeControls=true"))) {
            NumericalActiveRowsControls.run(out)
            return
        }
        if ("feedbackControls=true" in args) {
            feedbackControls(args, out)
            return
        }
        capture(args, out)
    }

    private fun capture(args: Array<String>, out: File) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val supported = setOf("suite", "problem", "sha256", "exact", "probe", "history", "frontier", "cuts",
            "selection", "replay", "operations", "activeRows", "supportBranching", "pointCuts", "aggregate-probe",
            "aggregate-mixed", "aggregate-replay", "aggregateCuts", "refutationReuse", "feedbackDelivery",
            "feedbackRequest", "solveSeconds", "certificateState")
        require(options.keys.containsAll(setOf("suite", "problem", "sha256", "exact")) &&
            (options.keys - supported).isEmpty())
        val probe = options["probe"]?.toBooleanStrict() ?: false
        val historyEnabled = options["history"]?.toBooleanStrict() ?: false
        val frontierEnabled = options["frontier"]?.toBooleanStrict() ?: false
        val activeOption = options["activeRows"] ?: "false"
        require(activeOption in setOf("false", "true", "compare"))
        val activeRows = activeOption == "true"
        val aggregateProbe = options["aggregate-probe"]?.toBooleanStrict() ?: false
        val aggregateMixed = options["aggregate-mixed"]?.toBooleanStrict() ?: false
        val aggregateReplay = options["aggregate-replay"]?.toBooleanStrict() ?: false
        require(!aggregateMixed || aggregateProbe)
        require(!aggregateReplay || aggregateProbe)
        val exact = options.getValue("exact").toBooleanStrict()
        val selected = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(selected.source)).use { it.readNBytes(16 * 1024 * 1024 + 1) }
        require(bytes.size <= 16 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.mps").writeBytes(bytes)
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val preparationStop = Cancellation.after(20.seconds)
        val parsed = Mps.parse(bytes.decodeToString())
        val original = checkNotNull(parsed.toLinearSourceProblem(
            if (exact) SemanticOverrides(feasibilityTolerance = SourceTolerance()) else SemanticOverrides(),
            preparationStop,
        ))
        val prepared = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(original, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
            maximize = original.objective?.sense == ObjectiveSense.MAXIMIZE,
            cancellation = preparationStop, linearTraversal = if (frontierEnabled) LinearTraversal.NUMERICAL_FRONTIER
                else LinearTraversal.NUMERICAL,
        ))
        require(prepared.route is SourceProblemRoute.NativeFormula)
        val execution = checkNotNull(field(prepared.route, "execution"))
        val request = field(execution, "request") as NumericalLinearRequest
        val solveSeconds = options["solveSeconds"]?.toInt()?.also { require(it in 1..600) }
        // An explicit solve deadline starts after preparation, so an operation-capped stream is not cut by parsing.
        val solveStop = if (solveSeconds == null) preparationStop or Cancellation.after(10.seconds)
            else Cancellation.after(solveSeconds.seconds)
        val supportBranching = options["supportBranching"]?.toBooleanStrict() ?: false
        val feedbackDelivery = options["feedbackDelivery"] ?: "latest"
        require(feedbackDelivery in setOf("latest", "prefix"))
        val feedbackRequest = options["feedbackRequest"] ?: "requested"
        require(feedbackRequest in setOf("requested", "full"))
        val params = TheoryParams(timeout = solveStop, cancellation = solveStop, randomSeed = 3L,
            sourceCuts = options["cuts"]?.toBooleanStrict() ?: true,
            sourcePointCuts = options["pointCuts"]?.toBooleanStrict() ?: false,
            sourceAggregateCuts = options["aggregateCuts"]?.toBooleanStrict() ?: false,
            sourceRefutationReuse = options["refutationReuse"]?.toBooleanStrict() ?: false,
            sourceLpOperations = options["operations"]?.toInt()?.also { require(it in 1..256) } ?: Int.MAX_VALUE)
        val authority = request.javaClass.declaredMethods.single {
            it.name == "authorityContext" && it.parameterCount == 2
        }.apply { isAccessible = true }
        val context = authority.invoke(request, checkNotNull(request.objective), solveStop) as SourceProofContext
        require(frontierEnabled || context.coordinates.size <= 128)
        val linear = checkNotNull(field(prepared, "linearSource"))
        val view = checkNotNull(field(linear, "view"))
        val scale = field(view, "objectiveScale") as BigFraction
        if (activeOption == "compare") {
            require(!probe && !historyEnabled)
            Reports.writeJson(out.resolve("active-source.json").path, buildJsonObject {
                put("suite", options.getValue("suite"))
                put("problem", selected.name)
                put("originalSourceSha256", sha256(bytes))
                put("sourceSemantics", original.semantics.toString())
                put("exactRequested", exact)
                put("objectiveScale", scale.toString())
                put("claimsCoverTolerance", field(linear, "claimsCoverTolerance") as Boolean)
            })
            NumericalActiveRowsControls.compare(out, selected.name, context, params, frontierEnabled,
                sourceKind = "original-mps-native-authority")
            return
        }
        val system = NumericalFrontierSnapshot.system(context)
        Reports.writeJson(out.resolve("native-system.json").path, system)
        val invocation = NumericalFrontierSnapshot.open(context, params, historyEnabled, frontierEnabled, activeRows,
            supportBranching, feedbackDelivery == "latest", feedbackRequest == "full")
        val selection = NumericalSelectionSnapshot(Cancellation.after(30.seconds))
        val journal = if (options["selection"]?.toBooleanStrict() == true || aggregateProbe) {
            selection.attach(invocation)
        } else null
        val history = ArrayList<JsonObject>()
        out.resolve("incumbents.jsonl").writeText("")
        out.resolve("bounds.jsonl").writeText("")
        val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val startedBytes = allocation.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        var lastBound: String? = null
        try {
            val result = NumericalFrontierSnapshot.run(invocation, onProgress = progress@{ owner ->
                val component = checkNotNull(field(owner, "component"))
                val bound = field(component, "rootBound") as SourceEvidence? ?: return@progress
                val value = ((bound.claim as SourceClaim.Bound).value as SourceValue.Rational).value
                if (lastBound == value.toString()) return@progress
                lastBound = value.toString()
                val progress = stats(owner)
                val entry = buildJsonObject {
                    put("elapsedNs", System.nanoTime() - started)
                    put("checks", progress.openTheory.openTheoryChecks)
                    put("adapterOperations", progress.smt.sourceLp.operations)
                    put("nativeValue", value.toString())
                    put("sourceUnitValue", (value * scale.reciprocal()).toString())
                    put("checkedByNativeOwner", true)
                    put("independentProofCheck", false)
                }
                out.resolve("bounds.jsonl").appendText(entry.toString() + "\n")
            }) { _, value ->
                val node = current(invocation)
                val nodeResult = checkNotNull(field(node, "result"))
                val bound = field(nodeResult, "bound") as SourceEvidence?
                val progress = stats(invocation)
                val entry = buildJsonObject {
                    put("elapsedNs", System.nanoTime() - started)
                    put("checks", progress.openTheory.openTheoryChecks)
                    put("adapterOperations", progress.smt.sourceLp.operations)
                    put("nativeValue", value.toString())
                    put("sourceUnitValue", (value * scale.reciprocal()).toString())
                    put("condition", condition(field(node, "condition") as SourceCondition))
                    put("acceptedBy", "LinearAuthorityInvocation.offer")
                    bound?.let {
                        put("nodeBoundNative", ((it.claim as SourceClaim.Bound).value as SourceValue.Rational)
                            .value.toString())
                    }
                }
                history += entry
                out.resolve("incumbents.jsonl").appendText(entry.toString() + "\n")
            }
            val solveElapsed = System.nanoTime() - started
            val solveAllocated = allocation.getThreadAllocatedBytes(thread) - startedBytes
            val captureStop = Cancellation.after(10.seconds)
            val snapshot = NumericalFrontierSnapshot(context, captureStop, scale)
            // A snapshot past its inspection allowance leaves the solve record intact.
            val frontier = runCatching { snapshot.capture(invocation) }.getOrElse {
                buildJsonObject { put("unavailable", it.message) }
            }
            if (options["certificateState"]?.toBooleanStrict() == true) {
                val certificate = runCatching { snapshot.certificateState(invocation) }.getOrElse {
                    buildJsonObject { put("unavailable", it.message) }
                }
                Reports.writeJson(out.resolve("certificate-state.json").path, certificate)
            }
            runCatching { Reports.writeJson(out.resolve("cuts.json").path, snapshot.cuts(invocation)) }
            if (journal != null) {
                Reports.writeJson(out.resolve("selection.json").path, selection.capture(journal))
                if (options["replay"]?.toBooleanStrict() == true) {
                    out.resolve("selection-replays.jsonl").writeText("")
                    NumericalSelectionSnapshot(Cancellation.after(10.seconds)).replay(journal) {
                        out.resolve("selection-replays.jsonl").appendText(it.toString() + "\n")
                    }
                }
                if (aggregateProbe) {
                    Reports.writeJson(out.resolve("aggregate-parents.json").path,
                        NumericalSelectionSnapshot(Cancellation.after(20.seconds)).capture(journal))
                    out.resolve("aggregate-pairs.jsonl").writeText("")
                    var replayParent: Pair<Any, List<Any>>? = null
                    NumericalAggregateSnapshot(Cancellation.after(20.seconds), aggregateMixed).inspect(journal,
                        onAccepted = { record, cuts ->
                            if (aggregateReplay && replayParent == null) replayParent = record to cuts
                        }) {
                        out.resolve("aggregate-pairs.jsonl").appendText(it.toString() + "\n")
                    }
                    replayParent?.let { (record, candidates) ->
                        out.resolve("aggregate-replays.jsonl").writeText("")
                        NumericalAggregateSnapshot(Cancellation.after(30.seconds)).replay(record, candidates) {
                            out.resolve("aggregate-replays.jsonl").appendText(it.toString() + "\n")
                        }
                    }
                }
            }
            val terminal = field(result, "terminal") as SourceEvidence?
            val work = stats(invocation).smt.sourceLp
            Reports.writeJson(out.resolve("frontier.json").path, buildJsonObject {
                put("revision", Reports.readGitSha())
                put("suite", options.getValue("suite"))
                put("problem", options.getValue("problem"))
                put("sourceSha256", sha256(bytes))
                put("nativeSystemSha256", sha256(system.toString().toByteArray()))
                put("sourceSemantics", original.semantics.toString())
                put("exactRequested", exact)
                put("randomSeed", params.randomSeed)
                put("solveDeadlineSeconds", solveSeconds ?: 10)
                put("solveDeadlineIncludesPreparation", solveSeconds == null)
                put("solveElapsedNs", solveElapsed)
                put("solveThreadAllocatedBytes", solveAllocated)
                put("captureDeadlineSeconds", 10)
                put("captureNodeAllowance", 4096)
                put("processors", Runtime.getRuntime().availableProcessors())
                put("os", System.getProperty("os.name"))
                put("arch", System.getProperty("os.arch"))
                put("objectiveScale", scale.toString())
                put("claimsCoverTolerance", field(linear, "claimsCoverTolerance") as Boolean)
                put("scope", NumericalFrontierSnapshot.scope(context.scope))
                put("measurement", "numerical-owner-state-capture")
                put("branchHistory", historyEnabled)
                put("boundOrdered", frontierEnabled)
                put("sourceCuts", params.sourceCuts)
                put("supportBranching", supportBranching)
                put("feedbackDelivery", feedbackDelivery)
                put("feedbackRequest", feedbackRequest)
                put("pointCuts", params.sourcePointCuts)
                put("aggregateCuts", params.sourceAggregateCuts)
                put("refutationReuse", params.sourceRefutationReuse)
                put("aggregateProbe", aggregateProbe)
                put("aggregateMixed", aggregateMixed)
                put("aggregateReplay", aggregateReplay)
                put("sourceLpOperationAllowance", params.sourceLpOperations)
                put("activeRows", activeRows)
                put("wholeCliTimingClaim", false)
                put("sourceVerdictClaim", false)
                put("independentWitnessCheck", false)
                put("reason", field(result, "reason").toString())
                put("maxLeaves", params.maxLeaves)
                put("maxDecisions", params.maxDecisions)
                put("openWorkLimit", params.openWorkLimit)
                put("incumbents", JsonArray(history))
                put("frontier", frontier)
                put("stages", buildJsonObject {
                    stats(invocation).smt.completeSearchStages.orEmpty().forEach { (key, value) -> put(key, value) }
                })
                put("sourceLp", buildJsonObject {
                    put("operations", work.operations)
                    put("activeNs", work.activeNs)
                    put("preparationWork", work.preparationWork)
                    put("floatWork", work.floatWork)
                    put("continuationWork", work.continuationWork)
                    put("refinementWork", work.refinementWork)
                    put("boundCertificationWork", work.boundCertificationWork)
                    put("conflictCertificationWork", work.conflictCertificationWork)
                })
                terminal?.let {
                    put("nativeTerminal", it.claim::class.simpleName)
                    put("nativeTerminalCheck", SourceEvidenceVerifier(context.withLinearAuthority())
                        .verify(it, captureStop).toString())
                }
            })
            if (probe) {
                out.resolve("root-probes.jsonl").writeText("")
                NumericalFrontierSnapshot(context, Cancellation.after(10.seconds), scale)
                    .probeRoot(invocation) { out.resolve("root-probes.jsonl").appendText(it.toString() + "\n") }
            }
        } finally {
            (invocation as AutoCloseable).close()
        }
    }

    private data class FeedbackArm(
        val name: String, val support: Boolean, val selection: Boolean, val reuse: Boolean, val aggregation: Boolean,
    )

    private fun feedbackControls(args: Array<String>, out: File) {
        val allowed = setOf("feedbackControls", "suite", "problem", "sha256", "exact", "operations")
        require(args.all { it.substringBefore('=') in allowed })
        require(args.any { it.startsWith("operations=") })
        val base = args.filterNot { it.startsWith("feedbackControls=") } +
            listOf("frontier=true", "selection=true")
        val arms = listOf(
            FeedbackArm("baseline", false, false, false, false),
            FeedbackArm("full", true, true, true, true),
            FeedbackArm("no-support", false, true, true, true),
            FeedbackArm("no-selection", true, false, true, true),
            FeedbackArm("no-reuse", true, true, false, true),
            FeedbackArm("no-aggregation", true, true, true, false),
        )
        for (arm in arms) {
            capture((base + listOf("supportBranching=${arm.support}", "pointCuts=${arm.selection}",
                "refutationReuse=${arm.reuse}", "aggregateCuts=${arm.aggregation}")).toTypedArray(),
                out.resolve(arm.name).apply { mkdirs() })
        }
    }

    private fun launch(args: Array<String>, out: File) {
        val command = arrayListOf(
            File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector",
            "-XX:StartFlightRecording=filename=${out.resolve("measured-profile.jfr")},settings=profile,dumponexit=true",
        )
        for (name in listOf("klause.workspace.root", "klause.bench.corpusCache")) {
            System.getProperty(name)?.let { command += "-D$name=$it" }
        }
        command += listOf("-cp", System.getProperty("java.class.path"), javaClass.name)
        command += args
        val builder = ProcessBuilder(command).redirectOutput(out.resolve("measured.stdout"))
            .redirectError(out.resolve("measured.stderr"))
        builder.environment()["KLAUSE_FRONTIER_MEASURED_JVM"] = "1"
        builder.environment()["OUT"] = out.path
        out.resolve("launcher.json").writeText(buildJsonObject {
            put("command", buildJsonArray { command.forEach { add(JsonPrimitive(it)) } })
        }.toString() + "\n")
        val process = builder.start()
        try {
            val solveSeconds = args.firstOrNull { it.startsWith("solveSeconds=") }?.substringAfter('=')?.toLong()
            val timeoutSeconds = if ("feedbackControls=true" in args) 360L else 120L + (solveSeconds ?: 0L)
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "frontier JVM transport timeout" }
            check(process.exitValue() == 0) { "frontier JVM failed" }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor()
            }
            out.resolve("measured.exit").writeText("${process.exitValue()}\n")
        }
    }
}
