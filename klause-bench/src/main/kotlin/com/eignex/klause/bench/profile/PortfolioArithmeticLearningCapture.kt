package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.factor.codec.MissingFactorCodecException
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceExecutionPolicy
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.util.ExecutionControl
import com.sun.management.OperatingSystemMXBean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.milliseconds

internal object PortfolioArithmeticLearningCapture {
    private class Snapshot(val json: String?, val decline: String?)

    private fun snapshot(codec: ProblemJson, problem: Problem): Snapshot {
        if (problem.numBoolVars > 4096 || problem.numIntVars > 4096) return Snapshot(null, "coordinate limit")
        return try {
            val json = codec.encodeToString(problem)
            if (json.encodeToByteArray().size <= 262_144) Snapshot(json, null) else Snapshot(null, "model byte limit")
        } catch (missing: MissingFactorCodecException) {
            Snapshot(null, missing.message?.take(512) ?: "missing factor codec")
        }
    }

    private class Observed(
        val worker: String,
        val ordinal: Int,
        val levelAtAttachment: Int,
        val learnedAtAttachment: Int,
        val model: String,
        val capture: PreparedArithmeticLearningProfile.Capture,
    )

    fun inspect(options: Map<String, String>, ref: ProblemRef, bytes: ByteArray, out: File, fingerprint: String) {
        val timeout = (options["timeout-ms"] ?: "10000").toLong()
        val seed = (options["seed"] ?: "1").toLong()
        val kind = options["learning"] ?: "integer"
        val traceMode = options["trace"] ?: "first-integer"
        val codec = ProblemJson()
        val cpu = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
        out.resolve("ordinary-learning.jsonl").bufferedWriter().use { records ->
            for (learning in listOf(false, true)) {
                val opened = System.nanoTime()
                val cpuBefore = cpu?.processCpuTime
                val stop = ExecutionControl.after(timeout.milliseconds)
                val setup = if (ref.format == Format.MPS) {
                    val source = requireNotNull(Mps.parse(bytes.decodeToString()).toLinearSourceProblem(
                        cancellation = stop))
                    SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
                        if (source.objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE,
                        FiniteEngine.BACKTRACK,
                        maximize = source.objective?.sense == ObjectiveSense.MAXIMIZE, cancellation = stop)
                } else {
                    val local = ProblemRef(ref.name, ref.format,
                        ProblemSource.Prepared(out.resolve("source.${ref.format.name.lowercase()}")),
                        Category.CSP, Expected.Unknown)
                    val source = Runners.resolve(local)
                    val objective = source.objective?.let { if (source.maximize) it.negated() else it }
                    SourceSetupRequest(SourceAttachment(source.problem),
                        if (objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE,
                        FiniteEngine.BACKTRACK, objective = objective, maximize = source.maximize, cancellation = stop)
                }
                val invocation = SourceSetup.prepare(setup)
                val sessions = IdentityHashMap<PropagationSession, Observed>()
                val ordered = ArrayList<Observed>()
                var callbacks = 0
                var omitted = 0
                var attachmentNs = 0L
                val snapshotDeclines = ArrayList<String>()
                val pairs = listOf("integer-row-learning=${learning && kind != "pb"}",
                    "pb-learning=${learning && kind != "integer"}", "portfolio-progress=false")
                val policy = if (options["lp"] == "off") {
                    SourceExecutionPolicy(lpCeiling = LpConfig.OFF, portfolioLpCeiling = LpConfig.OFF)
                } else SourceExecutionPolicy()
                val request = SourceExecutionRequest(invocation, policy, ExecutionOptions.parse(pairs),
                    cores = 1, randomSeed = seed, timeout = stop, cancellation = stop)
                val finiteRoute = SourceSetup.executionKind(invocation) == SourceExecutionKind.FINITE
                val originalSnapshot = if (finiteRoute) snapshot(codec, invocation.finiteShape().problem)
                else Snapshot(null, "finite route unavailable")
                val original = originalSnapshot.json
                var observed = request
                if (finiteRoute) {
                    val observer: (String, PropagationSession) -> Unit = observer@{ worker, native ->
                        callbacks++
                        if (sessions.containsKey(native)) return@observer
                        if (sessions.size >= 4 || callbacks > 16) {
                            omitted++
                            return@observer
                        }
                        val started = System.nanoTime()
                        try {
                            val payload = snapshot(codec, native.problem)
                            val model = payload.json
                            if (model == null) {
                                snapshotDeclines += "$worker: ${payload.decline}"
                                omitted++
                                return@observer
                            }
                            val capture = PreparedArithmeticLearningProfile.Capture(native, 1024, 65536,
                                captureFirstInteger = learning && kind != "pb" && traceMode == "first-integer",
                                captureFirstPb = learning && kind != "integer" && traceMode == "first-pb")
                            capture.record("ordinary.session-created", sessions.size.toLong(),
                                native.decisionLevel.toLong(), native.learnedClauseCount.toLong())
                            val entry = Observed(worker, ordered.size, native.decisionLevel,
                                native.learnedClauseCount, model, capture)
                            sessions[native] = entry
                            ordered += entry
                        } finally {
                            attachmentNs += System.nanoTime() - started
                        }
                    }
                    val attach = request.javaClass.methods.single {
                        it.name.startsWith("withPortfolioSessionObserver") && it.parameterCount == 1
                    }
                    observed = attach.invoke(request, observer) as SourceExecutionRequest
                }
                val result = SourceSetup.execute(observed)
                val elapsed = System.nanoTime() - opened
                val cpuAfter = cpu?.processCpuTime
                val finite = (result as? SourceExecutionResult.Finite)?.result
                val completed = finite?.outcome as? FiniteSolveOutcome.Completed
                val label = if (learning) kind else "clause"
                original?.let { out.resolve("$label-original.json").writeText(it) }
                val preparedSnapshot = finite?.let { snapshot(codec, it.preparation.problem) }
                preparedSnapshot?.json?.let { out.resolve("$label-prepared.json").writeText(it) }
                for (session in ordered) {
                    val index = session.ordinal
                    out.resolve("$label-worker-$index.json").writeText(session.model)
                    records.appendLine(buildJsonObject {
                        put("event", "native-capture")
                        put("revision", Reports.readGitSha())
                        put("provenanceFingerprint", fingerprint)
                        put("sourceSha256", sha256(bytes))
                        put("route", "ordinary-auto-catalog")
                        put("seed", seed)
                        put("worker", session.worker)
                        put("session", index)
                        put("levelAtAttachment", session.levelAtAttachment)
                        put("learnedAtAttachment", session.learnedAtAttachment)
                        put("integerRowLearning", learning && kind != "pb")
                        put("pbLearning", learning && kind != "integer")
                        put("original", original)
                        put("originalExportDecline", originalSnapshot.decline)
                        put("prepared", session.model)
                        put("preparedSha256", sha256(session.model.encodeToByteArray()))
                        put("lifecycle", session.capture.lifecycle())
                        put("finiteWork", session.capture.work())
                        put("trace", session.capture.export())
                        put("independentWitnessOrProofCheck", false)
                    }.toString())
                    session.capture.release()
                }
                val record = buildJsonObject {
                    put("event", "arm-result")
                    put("revision", Reports.readGitSha())
                    put("provenanceFingerprint", fingerprint)
                    put("sourceSha256", sha256(bytes))
                    put("route", "ordinary-auto-catalog")
                    put("seed", seed)
                    put("learning", label)
                    put("parameters", JsonArray(pairs.map(::JsonPrimitive)))
                    put("executionKind", result::class.simpleName)
                    put("sourceSemantics", invocation.source.semantics.toString())
                    put("verdict", completed?.verdict?.name)
                    put("stats", completed?.stats?.toString())
                    put("nativeSessionCallbacks", callbacks)
                    put("capturedSessions", sessions.size)
                    put("omittedSessionCallbacks", omitted)
                    put("snapshotDeclines", JsonArray(snapshotDeclines.map(::JsonPrimitive)))
                    put("originalExportDecline", originalSnapshot.decline)
                    put("preparedExportDecline", preparedSnapshot?.decline)
                    put("attachmentNs", attachmentNs)
                    put("executionNs", elapsed)
                    put("processCpuNs", if (cpuBefore != null && cpuBefore >= 0L &&
                        cpuAfter != null && cpuAfter >= 0L)
                        JsonPrimitive(cpuAfter - cpuBefore) else JsonNull)
                    put("cancelled", stop())
                    put("independentWitnessOrProofCheck", false)
                    put("performanceClaim", false)
                }
                records.appendLine(record.toString())
                records.flush()
                println(record)
            }
        }
    }
}
