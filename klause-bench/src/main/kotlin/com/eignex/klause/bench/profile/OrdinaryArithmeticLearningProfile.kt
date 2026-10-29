package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.catalog.Catalog
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
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import java.io.File
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
        require(options.keys.all { it in setOf("suite", "problem", "sha256", "timeout-ms", "nodes", "lp") })
        val suite = requireNotNull(options["suite"])
        val name = requireNotNull(options["problem"])
        val hash = requireNotNull(options["sha256"])
        val timeout = (options["timeout-ms"] ?: "10000").toLong().also { require(it in 1..30_000L) }
        val nodes = (options["nodes"] ?: "4096").toLong().also { require(it in 1..100_000L) }
        val lp = options["lp"] ?: "auto"
        require(lp in setOf("auto", "off"))
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
        val ref = Catalog.uncapped(suite).problems.single { it.name == name }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(ref.source)).use { it.readNBytes(1_048_577) }
        require(bytes.size <= 1_048_576)
        out.resolve("source.${ref.format.name.lowercase()}").writeBytes(bytes)
        val observedHash = sha256(bytes)
        publish(buildJsonObject {
            put("event", "source")
            put("suite", suite)
            put("problem", name)
            put("expectedSha256", hash)
            put("observedSha256", observedHash)
        }.toString())
        check(observedHash == hash) { "source hash mismatch: expected $hash, observed $observedHash" }
        val codec = ProblemJson()
        out.resolve("ordinary-learning.jsonl").bufferedWriter().use { output ->
            for (learning in listOf(false, true)) {
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
                val base = entry.searchParams ?: BacktrackPresets.conflictDriven(randomSeed = 3L)
                val selector = base.variableSelector
                fun observing(delegate: VariableSelector): VariableSelector = object : VariableSelector by delegate {
                    override fun fresh(): VariableSelector = observing(delegate.fresh())
                    override fun pick(session: PropagationSession, rng: Random): VarRef? {
                        if (native == null) {
                            native = session
                            capture = PreparedArithmeticLearningProfile.Capture(session, 1024, 65536)
                        }
                        check(native === session)
                        val picked = delegate.pick(session, rng)
                        val recorder = requireNotNull(capture)
                        if (recorder.accepting) {
                            val domain = (picked as? VarRef.IntVar)?.let { session.intDomain(it.varId) }
                            recorder.record("traversal.pick", session.decisionLevel.toLong(),
                                if (picked is VarRef.IntVar) picked.varId.toLong() else -1L,
                                if (picked is VarRef.Bool) picked.varId.toLong() else -1L,
                                domain?.min ?: 0L, domain?.max ?: 0L)
                        }
                        return picked
                    }
                }
                val params: BacktrackParams = base.copy(
                    variableSelector = observing(selector), randomSeed = 3L, cancellation = stop,
                    maxDecisions = nodes, pbLearning = false, integerRowLearning = learning,
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
                val beforeRetirement = native?.learnedClauseCount
                val beforeLifecycle = capture?.lifecycle()
                capture?.record("traversal.finished")
                native?.popToLevel(0)
                native?.forgetLearnedClauses { _, _ -> false }
                capture?.record("traversal.retirement-checked")
                val record = buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("provenanceFingerprint", fingerprint)
                    put("original", originalJson)
                    put("prepared", preparedJson)
                    put("suite", suite)
                    put("problem", name)
                    put("sourceSha256", hash)
                    put("integerRowLearning", learning)
                    put("pbLearning", false)
                    put("lp", lp)
                    put("seed", 3)
                    put("maxDecisions", nodes)
                    put("timeoutMs", timeout)
                    put("verdict", verdict)
                    put("witness", witness)
                    put("nativeReconstructedPointCheck", checked)
                    put("independentWitnessOrProofCheck", false)
                    put("presolve", prepared.presolve.toString())
                    put("stats", stats)
                    put("lifecycleBeforeRetirement", beforeLifecycle ?: JsonNull)
                    put("lifecycleAfterRetirement", capture?.lifecycle() ?: JsonNull)
                    put("storedBeforeRetirement", beforeRetirement)
                    put("storedAfterRetirement", native?.learnedClauseCount)
                    put("finiteWorkIncludingRetirement", capture?.work())
                    put("armElapsedBeforeSerializationNs", System.nanoTime() - opened)
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
