package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
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
import java.io.File
import java.lang.management.ManagementFactory
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PortfolioArithmeticLearningCapture {
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
        val cpu = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
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
                val pairs = listOf("integer-row-learning=${learning && kind != "pb"}",
                    "pb-learning=${learning && kind != "integer"}", "portfolio-progress=false")
                val policy = if (options["lp"] == "off") {
                    SourceExecutionPolicy(lpCeiling = LpConfig.OFF, portfolioLpCeiling = LpConfig.OFF)
                } else SourceExecutionPolicy()
                val request = SourceExecutionRequest(invocation, policy, ExecutionOptions.parse(pairs),
                    cores = 1, randomSeed = seed, timeout = stop, cancellation = stop)
                val original = if (SourceSetup.executionKind(invocation) == SourceExecutionKind.FINITE)
                    codec.encodeToString(invocation.finiteShape().problem) else null
                require(original == null || original.encodeToByteArray().size <= 262_144)
                var observed = request
                if (original != null) {
                    val observer: (String, PropagationSession) -> Unit = observer@{ worker, native ->
                        callbacks++
                        if (sessions.containsKey(native)) return@observer
                        if (sessions.size >= 4) {
                            omitted++
                            return@observer
                        }
                        val started = System.nanoTime()
                        try {
                            require(native.problem.numBoolVars <= 4096 && native.problem.numIntVars <= 4096)
                            val model = codec.encodeToString(native.problem)
                            require(model.encodeToByteArray().size <= 262_144)
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
                finite?.let {
                    out.resolve("$label-prepared.json").writeText(codec.encodeToString(it.preparation.problem))
                }
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
                    put("attachmentNs", attachmentNs)
                    put("executionNs", elapsed)
                    put("processCpuNs", if (cpuBefore != null && cpuAfter != null)
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
