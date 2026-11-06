package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** Remote-only ordered BT observations with an unobserved control for each source and seed. */
object BtTrajectoryCapture {
    /** Capture one hash-pinned CNF; timings and native verdicts are diagnostic observations. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256", "seeds"))
        val source = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(source.source)).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
        require(bytes.size <= MAX_SOURCE_BYTES && sha256(bytes) == options.getValue("sha256"))
        val seeds = options.getValue("seeds").split(',').map(String::toLong)
        require(seeds.size in 1..2 && seeds.distinct().size == seeds.size)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("source.cnf").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.txt").writeText(args.joinToString("\n", postfix = "\n"))
        val records = out.resolve("records.jsonl")
        for (seed in seeds) {
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
                    BacktrackSolver(model).solve(params)
                } finally {
                    capture?.close()
                }
                val elapsed = System.nanoTime() - start
                records.appendText(buildJsonObject {
                    put("sourceSha256", sha256(bytes))
                    put("problem", source.name)
                    put("seed", seed)
                    put("recipe", "clausal-balanced")
                    put("engine", "direct-backtrack")
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
        }
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
