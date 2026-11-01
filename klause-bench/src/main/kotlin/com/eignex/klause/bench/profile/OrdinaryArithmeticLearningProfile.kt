package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
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
import com.eignex.klause.bench.tools.CliProfile
import com.eignex.klause.formats.json.ProblemJson
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.portfolioExecutor
import com.eignex.klause.solver.search.SearchBranchAttemptObserver
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import java.io.File
import java.io.PrintStream
import java.lang.management.ManagementFactory
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal object OrdinaryArithmeticLearningProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all {
            it in setOf("suite", "problem", "sha256", "timeout-ms", "nodes", "lp", "trace", "source64", "format",
                "attribution", "recipe", "seed", "worker-seed", "route", "samples", "models")
        })
        val suite = requireNotNull(options["suite"])
        val name = requireNotNull(options["problem"])
        val hash = requireNotNull(options["sha256"])
        val timeout = (options["timeout-ms"] ?: "10000").toLong().also { require(it in 1..30_000L) }
        val nodes = (options["nodes"] ?: "4096").toLong().also { require(it in 1..100_000L) }
        val traceMode = options["trace"] ?: "first-integer"
        require(traceMode in setOf("initial", "first-integer", "none"))
        val tracing = traceMode != "none"
        val samples = (options["samples"] ?: "false").toBooleanStrict()
        val models = (options["models"] ?: "true").toBooleanStrict()
        val lp = options["lp"] ?: "auto"
        require(lp in setOf("auto", "off"))
        val attribution = (options["attribution"] ?: "false").toBooleanStrict()
        val route = options["route"] ?: "single"
        require(route in setOf("single", "portfolio"))
        val recipe = options["recipe"] ?: if (route == "portfolio") "conflictDriven" else "source"
        require(recipe in setOf("source", "satOptimized", "conflictDriven"))
        val seed = (options["seed"] ?: "3").toLong()
        val workerSeed = options["worker-seed"]?.toLong() ?: if (route == "portfolio") seed + 1001L else seed
        val targetIndex = workerSeed - seed - 1000L
        if (route == "portfolio") {
            require(recipe != "source" && targetIndex in 0L..1L)
        }
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
                val label = if (learning) "integer" else "clause"
                val recording = if (samples) Recording(Configuration.getConfiguration("profile")).apply {
                    maxSize = 33_554_432L
                    start()
                } else null
                val allocatedBefore = allocationBean?.getThreadAllocatedBytes(thread) ?: -1L
                val cpuBefore = cpuBean?.processCpuTime ?: -1L
                val opened = System.nanoTime()
                val stop = Cancellation.after(timeout.milliseconds)
                val entry = Runners.resolve(ref)
                val original = entry.problem
                require(original.numIntVars <= 4096 && original.numBoolVars <= 4096)
                val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                    original, FiniteEngine.BACKTRACK, entry.objective,
                    cancellation = stop, solveCancellation = stop, randomSeed = seed,
                ))
                val originalJson = if (models) codec.encodeToString(original) else null
                val preparedJson = if (models) codec.encodeToString(prepared.problem) else null
                if (originalJson != null && preparedJson != null) {
                    require(originalJson.encodeToByteArray().size <= 131_072 &&
                        preparedJson.encodeToByteArray().size <= 131_072)
                    out.resolve("$label-original.json").writeText(originalJson)
                    out.resolve("$label-prepared.json").writeText(preparedJson)
                }
                var native: PropagationSession? = null
                var lastSession: PropagationSession? = null
                var ignoredSessionAttachments = 0
                var targetRecipe: String? = null
                var capture: PreparedArithmeticLearningProfile.Capture? = null
                var picks = 0
                var attempts = 0
                var attachment: String? = null
                var integerRowsAtCapture: Long? = null
                val base = when (recipe) {
                    "satOptimized" -> BacktrackPresets.satOptimized(randomSeed = workerSeed)
                    "conflictDriven" -> BacktrackPresets.conflictDriven(randomSeed = workerSeed)
                    else -> entry.searchParams ?: BacktrackPresets.conflictDriven(randomSeed = workerSeed)
                }
                fun attach(session: PropagationSession, origin: String): Boolean {
                    if (lastSession !== session) {
                        lastSession = session
                        if (native != null && native !== session) ignoredSessionAttachments++
                    }
                    if (native == null) {
                        native = session
                        attachment = origin
                        val recorder = PreparedArithmeticLearningProfile.Capture(
                            session, 1024, 65536, captureFirstInteger = learning && traceMode == "first-integer",
                        )
                        capture = recorder
                        integerRowsAtCapture = recorder.lifecycle()["learningIntegerAdmitted"]
                            ?.jsonPrimitive?.longOrNull ?: 0L
                    }
                    if (native !== session) {
                        check(route == "portfolio")
                        return false
                    }
                    return true
                }
                fun observing(delegate: VariableSelector): VariableSelector = object : VariableSelector by delegate {
                    override fun fresh(): VariableSelector = observing(delegate.fresh())
                    override fun onSessionCreated(session: PropagationSession) {
                        attach(session, "session-created")
                        delegate.onSessionCreated(session)
                    }
                    override fun pick(session: PropagationSession, rng: Random): VarRef? {
                        if (!attach(session, "selector-fallback")) return delegate.pick(session, rng)
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
                fun observingParams(originalParams: BacktrackParams): BacktrackParams = if (!tracing) {
                    originalParams.copy(propagationAttribution = attribution)
                } else originalParams.copy(
                    variableSelector = observing(originalParams.variableSelector),
                    propagationAttribution = attribution,
                    branchObserver = object : SearchBranchAttemptObserver {
                        override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                            originalParams.branchObserver?.onAttempt(decision, context)
                            if (lastSession !== native) return
                            attempts++
                            capture?.record("traversal.attempt", context.decisionLevel.toLong(),
                                context.atomLiteral(decision)?.toLong() ?: -1L, attempts.toLong())
                        }
                    },
                )
                val params = observingParams(base.copy(
                    randomSeed = workerSeed, cancellation = stop, maxDecisions = nodes,
                    pbLearning = false, integerRowLearning = learning,
                    lpConfig = if (lp == "auto") LpConfig.AUTO else null,
                ))
                var verdict = "preparation-terminal"
                var witness: String? = null
                var checked = false
                var stats: String? = null
                val model = prepared.problem as? BakedProblem
                if (model != null && prepared.presolve?.infeasible != true && !stop()) {
                    val objective = prepared.objective
                    val portfolio = if (route == "portfolio") {
                        require(entry.searchParams == null && objective != null && model.numIntVars > 0 &&
                            model.numBoolVars == 0 && model.numRealVars == 0)
                        require((0 until model.numIntVars).all {
                            model.intBounds.hasLower(it) && model.intBounds.hasUpper(it) &&
                                model.intBounds.lower(it) >= 0L && model.intBounds.upper(it) <= 1L
                        })
                        val scenario = PortfolioScenario.sequential(Kind.COP, EngineMix.BACKTRACK, seed).copy(
                            lpCeiling = if (lp == "auto") LpConfig.AGGRESSIVE else LpConfig.OFF,
                            nodeBudget = NodeBudget(nodes),
                            btEdit = { originalParams ->
                                val edited = originalParams.copy(cancellation = stop, maxDecisions = nodes,
                                    integerRowLearning = learning && originalParams.integerRowLearning)
                                if (originalParams.randomSeed == workerSeed) observingParams(edited) else edited
                            },
                        )
                        targetRecipe = BacktrackCatalog.ranked(Kind.COP)[targetIndex.toInt()].label
                        check(targetRecipe == recipe) { "target worker is $targetRecipe, expected $recipe" }
                        FinitePipeline.portfolioExecutor(model, scenario, objective, null, null, null)
                    } else null
                    val sample = try {
                        if (objective == null) {
                            val result = portfolio?.solve(stop) ?: BacktrackSolver(model).solve(params)
                            verdict = result::class.simpleName ?: "unknown"
                            stats = result.stats.toString()
                            (result as? SolveResult.Sat)?.assignment
                        } else {
                            val result = portfolio?.minimize(stop) ?: BacktrackSolver(model).minimize(objective, params)
                            verdict = result::class.simpleName ?: "unknown"
                            stats = result.stats.toString()
                            result.assignment
                        }
                    } finally {
                        portfolio?.close()
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
                if (recording != null) {
                    recording.use {
                        it.stop()
                        val file = out.resolve("$label.jfr")
                        it.dump(file.toPath())
                        PrintStream(out.resolve("$label-samples.txt")).use { summary ->
                            CliProfile.summarizeCli(file, summary, "$label invocation")
                        }
                    }
                }
                val record = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("provenanceFingerprint", fingerprint)
                    put("original", originalJson)
                    put("prepared", preparedJson)
                    put("modelsExported", models)
                    put("originalIntegers", original.numIntVars)
                    put("originalFactors", original.factors.size)
                    put("preparedIntegers", prepared.problem.numIntVars)
                    put("preparedFactors", prepared.problem.factors.size)
                    put("suite", suite)
                    put("problem", name)
                    put("sourceSha256", hash)
                    put("sourceMode", if (source64 == null) "catalog" else "frozen")
                    put("integerRowLearning", learning)
                    put("pbLearning", if (route == "single") false else null)
                    put("propagationAttribution", attribution)
                    put("lp", lp)
                    put("seed", seed)
                    put("workerSeed", workerSeed)
                    put("recipe", recipe)
                    put("route", route)
                    put("targetRecipe", targetRecipe)
                    put("ignoredSessionAttachments", if (tracing) ignoredSessionAttachments else null)
                    put("maxDecisions", nodes)
                    put("timeoutMs", timeout)
                    put("traceMode", traceMode)
                    put("sampleRecording", if (samples) "$label.jfr" else null)
                    put("sampleRecordingMaxBytes", if (samples) 33_554_432L else null)
                    put("selectorPicks", if (tracing) picks else null)
                    put("branchAttempts", if (tracing) attempts else null)
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
