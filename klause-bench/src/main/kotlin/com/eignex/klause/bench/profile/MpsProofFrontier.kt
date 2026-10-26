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
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
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
            launch(args, out)
            return
        }
        require(Runtime.getRuntime().availableProcessors() == 1)
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val supported = setOf("suite", "problem", "sha256", "exact", "probe", "history", "frontier", "cuts",
            "selection", "replay", "operations")
        require(options.keys.containsAll(setOf("suite", "problem", "sha256", "exact")) &&
            (options.keys - supported).isEmpty())
        val probe = options["probe"]?.toBooleanStrict() ?: false
        val historyEnabled = options["history"]?.toBooleanStrict() ?: false
        val frontierEnabled = options["frontier"]?.toBooleanStrict() ?: false
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
        val solveStop = preparationStop or Cancellation.after(10.seconds)
        val params = TheoryParams(timeout = solveStop, cancellation = solveStop, randomSeed = 3L,
            sourceCuts = options["cuts"]?.toBooleanStrict() ?: true,
            sourceLpOperations = options["operations"]?.toInt()?.also { require(it in 1..256) } ?: Int.MAX_VALUE)
        val authority = request.javaClass.declaredMethods.single {
            it.name == "authorityContext" && it.parameterCount == 2
        }.apply { isAccessible = true }
        val context = authority.invoke(request, checkNotNull(request.objective), solveStop) as SourceProofContext
        require(frontierEnabled || context.coordinates.size <= 128)
        val linear = checkNotNull(field(prepared, "linearSource"))
        val view = checkNotNull(field(linear, "view"))
        val scale = field(view, "objectiveScale") as BigFraction
        val system = NumericalFrontierSnapshot.system(context)
        Reports.writeJson(out.resolve("native-system.json").path, system)
        val invocation = NumericalFrontierSnapshot.open(context, params, historyEnabled, frontierEnabled)
        val selection = NumericalSelectionSnapshot(Cancellation.after(30.seconds))
        val journal = if (options["selection"]?.toBooleanStrict() == true) selection.attach(invocation) else null
        val history = ArrayList<JsonObject>()
        out.resolve("incumbents.jsonl").writeText("")
        out.resolve("bounds.jsonl").writeText("")
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
            val captureStop = Cancellation.after(10.seconds)
            val snapshot = NumericalFrontierSnapshot(context, captureStop, scale)
            val frontier = snapshot.capture(invocation)
            Reports.writeJson(out.resolve("cuts.json").path, snapshot.cuts(invocation))
            if (journal != null) {
                Reports.writeJson(out.resolve("selection.json").path, selection.capture(journal))
                if (options["replay"]?.toBooleanStrict() == true) {
                    out.resolve("selection-replays.jsonl").writeText("")
                    NumericalSelectionSnapshot(Cancellation.after(10.seconds)).replay(journal) {
                        out.resolve("selection-replays.jsonl").appendText(it.toString() + "\n")
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
                put("solveDeadlineSeconds", 10)
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
                put("sourceLpOperationAllowance", params.sourceLpOperations)
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
        out.resolve("launcher.json").writeText(buildJsonObject {
            put("command", buildJsonArray { command.forEach { add(JsonPrimitive(it)) } })
        }.toString() + "\n")
        val process = builder.start()
        try {
            check(process.waitFor(120, TimeUnit.SECONDS)) { "frontier JVM transport timeout" }
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
