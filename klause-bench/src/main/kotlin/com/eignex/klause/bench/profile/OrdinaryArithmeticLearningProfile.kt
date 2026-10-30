package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object OrdinaryArithmeticLearningProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all {
            it in setOf("suite", "problem", "sha256", "timeout-ms", "nodes", "lp", "trace", "source64", "format",
                "attribution")
        })
        val suite = requireNotNull(options["suite"])
        val name = requireNotNull(options["problem"])
        val hash = requireNotNull(options["sha256"])
        val timeout = (options["timeout-ms"] ?: "10000").toLong().also { require(it in 1..30_000L) }
        val nodes = (options["nodes"] ?: "4096").toLong().also { require(it in 1..100_000L) }
        val traceMode = options["trace"] ?: "first-integer"
        require(traceMode in setOf("initial", "first-integer"))
        val lp = options["lp"] ?: "auto"
        require(lp in setOf("auto", "off"))
        val attribution = (options["attribution"] ?: "false").toBooleanStrict()
        val allocationBean = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported }?.apply { isThreadAllocatedMemoryEnabled = true }
        val cpuBean = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
        val thread = Thread.currentThread().threadId()
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        var stdoutBytes = 0L
        fun publish(record: String) {
            val bytes = record.encodeToByteArray().size + 1L
            require(bytes <= 3_145_728L - stdoutBytes) { "live record allowance exhausted" }
            stdoutBytes += bytes
            println(record)
        }
        val provenance = InstalledBuild.reference
        val fingerprint = provenance.fingerprint
        Reports.writeJson(out.resolve("provenance.json").path, provenance)
        publish(buildJsonObject {
            put("event", "provenance")
            put("fingerprint", fingerprint)
            put("build", Reports.json.encodeToString(provenance))
            put("javaRuntimeVersion", System.getProperty("java.runtime.version"))
            put("javaVmName", System.getProperty("java.vm.name"))
        }.toString())
        val source64 = options["source64"]
        val catalogRef = if (source64 == null) Catalog.uncapped(suite).problems.single { it.name == name } else null
        val format = catalogRef?.format ?: Format.valueOf(requireNotNull(options["format"]).uppercase())
        val bytes = if (source64 == null) {
            CorpusFiles.open(CorpusFetcher.resolve(requireNotNull(catalogRef).source)).use { it.readNBytes(1_048_577) }
        } else trajectorySourceBytes(suite, name, hash, source64)
        require(bytes.size <= 1_048_576)
        val sourceFile = out.resolve("source.${format.name.lowercase()}").apply { writeBytes(bytes) }
        val observedHash = sha256(bytes)
        publish(buildJsonObject {
            put("event", "source")
            put("suite", suite)
            put("problem", name)
            put("expectedSha256", hash)
            put("observedSha256", observedHash)
            put("sourceMode", if (source64 == null) "catalog" else "frozen")
        }.toString())
        check(observedHash == hash) { "source hash mismatch: expected $hash, observed $observedHash" }
        val ref = catalogRef ?: ProblemRef(name, format, ProblemSource.Prepared(sourceFile),
            Category.CSP, Expected.Unknown)
        val codec = ProblemJson()
        out.resolve("ordinary-learning.jsonl").bufferedWriter().use { output ->
            for (learning in listOf(false, true)) {
                val allocatedBefore = allocationBean?.getThreadAllocatedBytes(thread) ?: -1L
                val cpuBefore = cpuBean?.processCpuTime ?: -1L
                val opened = System.nanoTime()
                val stop = Cancellation.after(timeout.milliseconds)
                val entry = Runners.resolve(ref)
                val original = entry.problem
                require(original.numIntVars <= 4096 && original.numBoolVars <= 4096)
                val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                    original, FiniteEngine.BACKTRACK, entry.objective,
                    cancellation = stop, solveCancellation = stop, randomSeed = 3L,
                ))
                val label = if (learning) "integer" else "clause"
                val originalJson = codec.encodeToString(original)
                val preparedJson = codec.encodeToString(prepared.problem)
                require(originalJson.encodeToByteArray().size <= 131_072 &&
                    preparedJson.encodeToByteArray().size <= 131_072)
                out.resolve("$label-original.json").writeText(originalJson)
                out.resolve("$label-prepared.json").writeText(preparedJson)
                var native: PropagationSession? = null
                var capture: PreparedArithmeticLearningProfile.Capture? = null
                var picks = 0
                var attempts = 0
                var attachment: String? = null
                var integerRowsAtCapture: Long? = null
                val base = entry.searchParams ?: BacktrackPresets.conflictDriven(randomSeed = 3L)
                val selector = base.variableSelector
                fun attach(session: PropagationSession, origin: String) {
                    if (native == null) {
                        native = session
                        attachment = origin
                        integerRowsAtCapture = session.learnedLifecycle()["learningIntegerAdmitted"] ?: 0L
                        capture = PreparedArithmeticLearningProfile.Capture(
                            session, 1024, 65536, captureFirstInteger = learning && traceMode == "first-integer",
                        )
                    }
                    check(native === session)
                }
                fun observing(delegate: VariableSelector): VariableSelector = object : VariableSelector by delegate {
                    override fun fresh(): VariableSelector = observing(delegate.fresh())
                    override fun onSessionCreated(session: PropagationSession) {
                        attach(session, "session-created")
                        delegate.onSessionCreated(session)
                    }
                    override fun pick(session: PropagationSession, rng: Random): VarRef? {
                        attach(session, "selector-fallback")
                        val recorder = requireNotNull(capture)
                        picks++
                        val picked = delegate.pick(session, rng)
                        if (recorder.accepting) {
                            val domain = (picked as? VarRef.IntVar)?.let { session.intDomain(it.varId) }
                            recorder.record("traversal.pick", session.decisionLevel.toLong(),
                                if (picked is VarRef.IntVar) picked.varId.toLong() else -1L,
                                if (picked is VarRef.Bool) picked.varId.toLong() else -1L,
                                domain?.min ?: 0L, domain?.max ?: 0L, picks.toLong())
                        }
                        return picked
                    }
                }
                val params: BacktrackParams = base.copy(
                    variableSelector = observing(selector), randomSeed = 3L, cancellation = stop,
                    maxDecisions = nodes, pbLearning = false, integerRowLearning = learning,
                    propagationAttribution = attribution,
                    branchObserver = object : SearchBranchAttemptObserver {
                        override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                            base.branchObserver?.onAttempt(decision, context)
                            attempts++
                            capture?.record("traversal.attempt", context.decisionLevel.toLong(),
                                context.atomLiteral(decision)?.toLong() ?: -1L, attempts.toLong())
                        }
                    },
                    lpConfig = if (lp == "auto") LpConfig.AUTO else null,
                )
                var verdict = "preparation-terminal"
                var witness: String? = null
                var checked = false
                var stats: String? = null
                val model = prepared.problem as? BakedProblem
                if (model != null && prepared.presolve?.infeasible != true && !stop()) {
                    val solver = BacktrackSolver(model)
                    val objective = prepared.objective
                    val sample = if (objective == null) {
                        val result = solver.solve(params)
                        verdict = result::class.simpleName ?: "unknown"
                        stats = result.stats.toString()
                        (result as? SolveResult.Sat)?.assignment
                    } else {
                        val result = solver.minimize(objective, params)
                        verdict = result::class.simpleName ?: "unknown"
                        stats = result.stats.toString()
                        result.assignment
                    }
                    sample?.let {
                        val point = prepared.reconstruct(it)
                        checked = PreparedArithmeticLearningProfile.checkPoint(original, point, stop)
                        witness = "bools=${point.bools.joinToString()};ints=${point.ints.joinToString()}"
                    }
                }
                val liveAtInspection = native?.let { LivePropagationState.current(it) != null } == true
                val beforeRetirement = if (liveAtInspection) native.learnedClauseCount else null
                val beforeLifecycle = capture?.lifecycle()
                capture?.record("traversal.finished")
                if (liveAtInspection) {
                    native.popToLevel(0)
                    native.forgetLearnedClauses { _, _ -> false }
                    capture?.record("traversal.retirement-checked")
                }
                val elapsed = System.nanoTime() - opened
                val allocatedAfter = allocationBean?.getThreadAllocatedBytes(thread) ?: -1L
                val cpuAfter = cpuBean?.processCpuTime ?: -1L
                val record = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("provenanceFingerprint", fingerprint)
                    put("original", originalJson)
                    put("prepared", preparedJson)
                    put("suite", suite)
                    put("problem", name)
                    put("sourceSha256", hash)
                    put("sourceMode", if (source64 == null) "catalog" else "frozen")
                    put("integerRowLearning", learning)
                    put("pbLearning", false)
                    put("propagationAttribution", attribution)
                    put("lp", lp)
                    put("seed", 3)
                    put("maxDecisions", nodes)
                    put("timeoutMs", timeout)
                    put("traceMode", traceMode)
                    put("selectorPicks", picks)
                    put("branchAttempts", attempts)
                    put("captureAttachment", attachment)
                    put("integerRowsAtCapture", integerRowsAtCapture)
                    put("verdict", verdict)
                    put("witness", witness)
                    put("nativeReconstructedPointCheck", checked)
                    put("independentWitnessOrProofCheck", false)
                    put("presolve", prepared.presolve.toString())
                    put("stats", stats)
                    put("lifecycleBeforeRetirement", beforeLifecycle ?: JsonNull)
                    put("lifecycleAfterRetirement", capture?.lifecycle() ?: JsonNull)
                    put("storedBeforeRetirement", beforeRetirement)
                    put("storedAfterRetirement", if (liveAtInspection) native.learnedClauseCount else null)
                    put("retirementInspection", when {
                        liveAtInspection -> "manual-retraction"
                        native != null -> "solver-released"
                        else -> "unobserved"
                    })
                    put("finiteWorkIncludingRetirement", capture?.work())
                    put("armElapsedBeforeSerializationNs", elapsed)
                    put("armProcessCpuNs", if (cpuBefore >= 0L && cpuAfter >= cpuBefore) cpuAfter - cpuBefore else null)
                    put("armThreadAllocatedBytes", if (allocatedBefore >= 0L && allocatedAfter >= allocatedBefore)
                        allocatedAfter - allocatedBefore else null)
                    put("cancelled", stop())
                    put("trace", capture?.export() ?: JsonNull)
                }.toString()
                output.appendLine(record)
                output.flush()
                publish(record)
                capture?.release()
            }
        }
        System.out.flush()
        // The lab publishes live log tails asynchronously before process teardown.
        val chunks = (stdoutBytes + 65_535L) / 65_536L
        Thread.sleep(5_000L + chunks * 1_100L)
    }
}
