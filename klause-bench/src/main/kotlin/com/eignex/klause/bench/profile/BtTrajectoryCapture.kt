package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchCli
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchResources
import com.eignex.lab.bench.BenchResult
import com.eignex.lab.bench.BenchSuite
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** Remote-only ordered BT observations with an unobserved control for each source and seed. */
object BtTrajectoryCapture : BenchSuite {
    /** Execute the lab SDK protocol. */
    @JvmStatic
    fun main(args: Array<String>) = BenchCli.execute(this, args)

    override fun describe() = BenchDescriptor("bt-trajectory", "Bounded resumable backtrack trajectory observations")

    override fun plan(request: BenchRequest): List<BenchCase> {
        val options = request.parameters
        require(options.keys == setOf("source-path", "sha256", "seeds"))
        require(options.getValue("sha256").matches(Regex("[0-9a-f]{64}")))
        val seeds = options.getValue("seeds").split(',').map(String::toLong)
        require(seeds.size in 1..2 && seeds.distinct().size == seeds.size)
        val input = BenchInput(path = options.getValue("source-path"), root = BenchInputRoot.CORPUS,
            role = "model", sha256 = options.getValue("sha256"))
        return seeds.map { seed ->
            BenchCase("bt-trajectory/$seed", options - "seeds" + ("seed" to seed.toString()),
                BenchResources(cpus = 1, memoryMiB = 4096, timeoutSeconds = 180,
                    requiredTags = setOf("platform:linux", "arch:x86_64")),
                inputs = listOf(input),
                attributes = mapOf("comparisonKey" to "bt-trajectory/$seed", "seed" to seed.toString()))
        }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        System.setProperty("klause.workspace.root", requireNotNull(context.worktree).toString())
        val options = case.parameters
        require(options.keys == setOf("source-path", "sha256", "seed"))
        val source = requireNotNull(context.corpusDirectory).resolve(options.getValue("source-path")).toFile()
        val bytes = CorpusFiles.open(source).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
        require(bytes.size <= MAX_SOURCE_BYTES && sha256(bytes) == options.getValue("sha256"))
        val seed = options.getValue("seed").toLong()
        val out = context.outputDirectory.toFile()
        out.resolve("source.cnf").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        Reports.writeJson(out.resolve("request.json").path, options)
        val records = out.resolve("records.jsonl")
        for (observed in listOf(false, true)) {
            val capture = if (observed) Trace(out.resolve("$seed.trace.tsv")) else null
            val stop = Cancellation.after(60.seconds)
            val start = System.nanoTime()
            val model = Dimacs.parse(bytes.decodeToString()).toProblem().bake(cancellation = stop)
            val base = BacktrackCatalog.byLabel("clausal-balanced").build(seed, null)
            val listener: ((SearchEvent) -> Unit)? = if (capture == null) base.onEvent else { event ->
                base.onEvent?.invoke(event)
                capture.event(event)
            }
            val params = base.copy(
                nativeSat = true,
                cancellation = stop,
                variableSelector = capture?.let { Variables(base.variableSelector, it) } ?: base.variableSelector,
                valueSelector = capture?.let { Values(base.valueSelector, it) } ?: base.valueSelector,
                onEvent = listener,
            )
            val result = try {
                BacktrackSolver(model).resumableSolve(params).use { search ->
                    var verdict: SolveResult? = null
                    while (verdict == null) verdict = search.runSlice(stop, Long.MAX_VALUE, -1L)
                    verdict
                }
            } finally {
                capture?.close()
            }
            val elapsed = System.nanoTime() - start
            records.appendText(buildJsonObject {
                put("sourceSha256", sha256(bytes))
                put("problem", options.getValue("source-path"))
                put("seed", seed)
                put("recipe", "clausal-balanced")
                put("engine", "resumable-backtrack")
                put("sliceMillis", Long.MAX_VALUE)
                put("sliceWork", -1L)
                put("nativeSatRequested", true)
                put("presolve", false)
                put("localSearch", false)
                put("booleanProofTasks", false)
                put("observed", observed)
                put("firstControlInProcess", !observed)
                put("sourceBooleanVariables", model.numBoolVars)
                put("sourceIntegerVariables", model.numIntVars)
                put("sourceFactors", model.numFactors)
                put("result", result::class.simpleName)
                put("nativeSatClaim", result is SolveResult.Sat)
                put("nativeUnsatClaim", result is SolveResult.Unsat)
                put("independentSourceValidation", false)
                put("decisions", result.stats.search.nodes.sum)
                put("failures", result.stats.search.fails.sum)
                put("restarts", result.stats.search.restarts.sum)
                put("propagationWork", result.stats.search.propagationWork.sum)
                put("rootPropagationWork", result.stats.search.rootPropagationWork.sum)
                put("inclusiveElapsedNs", elapsed)
                put("traceEvents", capture?.events)
                put("traceWrittenEvents", capture?.written)
                put("traceTruncated", capture?.truncated)
                put("traceBytes", capture?.file?.length())
                put("traceSha256", capture?.file?.let { sha256(it.readBytes()) })
                put("performanceQualified", false)
            }.toString() + "\n")
            println("seed=$seed observed=$observed result=${result::class.simpleName} " +
                "decisions=${result.stats.search.nodes.sum} events=${capture?.events}")
        }
        return BenchResult(case.id, artifacts = listOf("source.cnf", "provenance.json", "request.json",
            "records.jsonl", "$seed.trace.tsv").map { context.artifact(it) }, details = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("provenanceFingerprint", InstalledBuild.reference.fingerprint)
            put("sourceSha256", sha256(bytes))
            put("seed", seed)
            put("controls", JsonArray(records.readLines().map { Reports.json.parseToJsonElement(it) }))
            put("performanceQualified", false)
        })
    }

    private class Variables(private val delegate: VariableSelector, private val trace: Trace) :
        VariableSelector by delegate {
        override fun fresh(): VariableSelector = Variables(delegate.fresh(), trace)

        override fun pick(session: PropagationSession, rng: Random): VarRef? = delegate.pick(session, rng).also {
            trace.picked(it, session)
        }
    }

    private class Values(private val delegate: ValueSelector, private val trace: Trace) : ValueSelector by delegate {
        override fun fresh(): ValueSelector = Values(delegate.fresh(), trace)

        override fun onCommit(varRef: VarRef, value: Long) {
            delegate.onCommit(varRef, value)
            trace.committed(varRef, value)
        }

        override fun onConflict(varRef: VarRef, value: Long) {
            delegate.onConflict(varRef, value)
            trace.conflicted(varRef, value)
        }
    }

    private class Trace(val file: File) : AutoCloseable {
        private val output: BufferedWriter = file.bufferedWriter()
        private var commits = 0L
        var events = 0L
            private set
        var written = 0L
            private set
        val truncated: Boolean get() = events > written

        fun picked(variable: VarRef?, session: PropagationSession) {
            row("P", kind(variable), variable?.varId?.toLong() ?: -1L)
            if (commits % CHECKPOINT == 0L) row("K", commits, session.learnedClauseCount.toLong())
        }

        fun committed(variable: VarRef, value: Long) {
            row("C", kind(variable), variable.varId.toLong(), value)
            commits++
        }

        fun conflicted(variable: VarRef, value: Long) {
            row("F", kind(variable), variable.varId.toLong(), value)
        }

        fun event(event: SearchEvent) {
            when (event) {
                is SearchEvent.Restart -> row("R", event.index, event.steps)
                is SearchEvent.LearnedDbSweep -> row("S", event.kept.toLong(), event.dropped.toLong())
                else -> Unit
            }
        }

        private fun row(tag: String, vararg values: Long) {
            events++
            if (written >= MAX_TRACE_EVENTS) return
            output.append(tag)
            for (value in values) output.append('\t').append(value.toString())
            output.append('\n')
            written++
        }

        override fun close() { output.close() }

        private fun kind(variable: VarRef?): Long = when (variable) {
            null -> 0L
            is VarRef.Bool -> 1L
            is VarRef.IntVar -> 2L
        }
    }

    private const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    private const val MAX_TRACE_EVENTS = 131_072L
    private const val CHECKPOINT = 256L
}
