package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.runner.MiniZincCompileFailure
import com.eignex.klause.bench.runner.MiniZincCompilerIdentity
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.ProblemKind
import com.eignex.klause.formats.flatzinc.UnsupportedFlatZincException
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import com.eignex.klause.formats.xcsp3.UnsupportedXcsp3Exception
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant

/** One problem's result for one settings+budget — the durable per-problem record the lab reads. */
@Serializable
internal data class SolveRecord(
    val problem: String,
    val solver: String, // always klause; lab reference records share the shape with their own solver id
    val engine: String?, // klause engine (cp/ls/mixed/…); null = the cli's default
    val processors: Int,
    val seed: Long,
    val budgetMs: Long,
    val kind: String, // "optimize" | "satisfy"
    /** True when the objective is maximized (higher is better) — for direction-aware comparison. */
    val maximize: Boolean,
    /** true = feasible, false = infeasible (proved), null = unknown within budget. */
    val feasible: Boolean?,
    val objective: Double?,
    /** ms to the best incumbent (optimize) or to the first solution (satisfy); null when none. */
    val timeToBestMs: Long?,
    /** ms to the first feasible solution; null when never feasible. */
    val timeToFirstFeasibleMs: Long? = null,
    /** optimum proved (optimize) or search closed UNSAT/exhausted. */
    val proven: Boolean,
    val stats: Map<String, String> = emptyMap(),
    /**
     * Per-arm portfolio improvement records in arrival order; empty for single-engine solves.
     * Discrete objectives are exact decimal text, with a continuous objective when needed.
     */
    val attribution: List<Attribution> = emptyList(),
    val gitSha: String?,
    val timestamp: String,
    val command: String,
    val buildProvenance: BuildProvenance? = null,
    val buildFingerprint: String? = null,
    val validationPolicy: String = REPORTED_RESULT_POLICY,
    /** Subprocess duration, separate from incumbent timings; null when unavailable. */
    val elapsedMs: Long? = null,
    /** Plain source bytes, independent of cache compression; null when unavailable. */
    val sourceHashes: Map<String, String> = emptyMap(),
    /** Final rendered candidate, retained for independent checks when the raw stream is not transferred. */
    val finalWitness: String? = null,
    val processTimeToFirstFeasibleMs: Long? = null,
    val processTimeToBestMs: Long? = null,
    val exactObjective: String? = null,
    val compilerIdentity: MiniZincCompilerIdentity? = null,
    /** Normalized subprocess stderr, limited to 16 Ki characters; null when unavailable. */
    val stderr: String? = null,
    /** Whether [stderr] contains the whole normalized stream rather than a bounded prefix. */
    val stderrComplete: Boolean = false,
)

internal object SolveMetric {
    /** Characters of a load failure's message kept in its record. */
    private const val REASON_CAP = 400
    private const val MAX_LOAD_CAUSE_DEPTH = 64

    /** Solve the one problem [ref] with klause-cli, writing its [SolveRecord] to `<outDir>/<problem>.json` and the
     *  raw solver output beside it, and return the record. A problem that fails to load gets a record too, its reason
     *  under `stats.unsupported` when klause declines the model and under `stats.loadError` otherwise. */
    fun solveOne(ref: ProblemRef, budget: Budget, settings: SolverInvocation.Settings, outDir: File): SolveRecord {
        SolverInvocation.klauseCliDefect()?.let { error("the klause dist cannot solve: $it") }
        outDir.mkdirs()
        val timestamp = Instant.now().toString()
        val sha = Reports.readGitSha()
        val name = ref.name.replace('/', '_')
        val (rec, raw) = runCatching { Runners.resolve(ref, settings.exact) }.fold(
            { entry ->
                if (ref.format == Format.MINIZINC) {
                    settings.captureDirectory?.let { directory ->
                    directory.mkdirs()
                    val runner = MiniZincRunner()
                    runner.compileFzn(ref).copyTo(directory.resolve("model.fzn"), overwrite = true)
                    runner.compileOutput(ref).copyTo(directory.resolve("model.ozn"), overwrite = true)
                }
                }
                solve(entry, settings, budget, timestamp, sha)
            },
            { failure ->
                if (failure is MiniZincCompileFailure) {
                    settings.captureDirectory?.let { directory ->
                        directory.mkdirs()
                        directory.resolve("compiler.log").writeText(failure.diagnostic)
                        val command = Reports.json.encodeToString(failure.command)
                        directory.resolve("compiler-command.json").writeText(command)
                    }
                }
                loadFailureRecord(ref, settings, budget, timestamp, sha, failure) to null
            },
        )
        raw?.let { File(outDir, "$name.out").writeText(it) }
        File(outDir, "$name.json").writeText(Reports.json.encodeToString(rec))
        return rec
    }

    /** The record of a problem that never reached the solver: undecided, with why under `stats`. */
    internal fun loadFailureRecord(
        ref: ProblemRef,
        s: SolverInvocation.Settings,
        budget: Budget,
        timestamp: String,
        sha: String?,
        failure: Throwable,
        sourceHashes: Map<String, String>? = null,
    ): SolveRecord {
        val reason = (failure.message ?: failure::class.simpleName.orEmpty()).take(REASON_CAP).lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ").take(REASON_CAP)
        return SolveRecord(
            problem = ref.name,
            solver = SolverInvocation.KLAUSE,
            engine = s.engine,
            processors = s.processors ?: 1,
            seed = s.seed,
            budgetMs = budget.timeoutMillis,
            kind = if (runCatching { ProblemKind.isCop(ref) }.getOrDefault(false)) "optimize" else "satisfy",
            maximize = false,
            feasible = null,
            objective = null,
            timeToBestMs = null,
            proven = false,
            stats = buildMap {
                put((if (declined(failure)) "unsupported" else "loadError"), reason)
                if (failure is MiniZincCompileFailure) {
                    put("compilerExit", failure.exitCode.toString())
                    put("compilerElapsedNs", failure.elapsedNs.toString())
                    failure.incompatibility?.let { put("sourceCompilerIncompatible", it) }
                }
            },
            sourceHashes = sourceHashes ?: (failure as? MiniZincCompileFailure)?.sourceHashes
                ?: runCatching { SolveEvidence.sourceHashes(ref) }.getOrDefault(emptyMap()),
            compilerIdentity = (failure as? MiniZincCompileFailure)?.compiler,
            gitSha = sha,
            timestamp = timestamp,
            command = "LOAD",
        )
    }

    /** Whether klause declined a model it read, rather than the model failing to compile or parse. */
    private fun declined(failure: Throwable): Boolean = generateSequence(failure) { it.cause }
        .take(MAX_LOAD_CAUSE_DEPTH).any {
            it is UnsupportedFlatZincException || it is UnsupportedXcsp3Exception || it is UnsupportedSmtException
        }

    /** One problem's record from a subprocess solve, with the raw solver output when the
     *  solve ran; a solve that throws becomes an error record. */
    internal fun solve(
        entry: ResolvedProblem,
        settings: SolverInvocation.Settings,
        budget: Budget,
        timestamp: String,
        sha: String?,
        installedBuild: () -> BuildProvenance? = { InstalledBuild.current },
        invoke: (ResolvedProblem, SolverInvocation.Settings, Budget, Boolean) -> SolverInvocation.Result =
            SolverInvocation::run,
        modelOutput: (ProblemRef, String) -> String = MiniZincRunner()::modelOutput,
    ): Pair<SolveRecord, String?> {
        val optimize = entry.objective != null
        val kind = if (optimize) "optimize" else "satisfy"
        var sourceHashes: Map<String, String> = emptyMap()
        var provenance: BuildProvenance? = null
        var compiler: MiniZincCompilerIdentity? = null
        return runCatching {
            sourceHashes = SolveEvidence.sourceHashes(entry.ref)
            provenance = installedBuild()
            compiler = if (entry.ref.format == Format.MINIZINC) MiniZincCompatibility.compiler else null
            val r = invoke(entry, settings, budget, optimize)
            // A float model Klause solves on a grid is marked, so a reader knows its claims are approximations.
            val approximation = if (entry.hasFloats) {
                mapOf("floatApproximation" to entry.floatApproximation.toString())
            } else {
                emptyMap()
            }
            val (witness, witnessStats) = if (r.feasible == true) {
                witness(entry.ref, r.rawOutput, modelOutput)
            } else {
                null to emptyMap()
            }
            record(entry, settings, budget, kind, timestamp, sha, r).let { reported ->
                reported.copy(
                    stats = reported.stats + approximation + witnessStats,
                    compilerIdentity = compiler,
                    sourceHashes = sourceHashes,
                    finalWitness = witness,
                )
            } to r.rawOutput
        }.getOrElse { failure ->
            val record = errorRecord(entry, settings, budget, kind, timestamp, sha)
                .copy(
                    stats = SolveFailure.stats(failure),
                    compilerIdentity = compiler,
                    sourceHashes = sourceHashes,
                    buildProvenance = provenance,
                    buildFingerprint = provenance?.fingerprint,
                )
            println("?? [${entry.name}] $kind ERROR: ${record.stats["errorMessage"] ?: record.stats["errorClass"]}")
            record to null
        }
    }

    /** [raw]'s final witness, with how it was mapped. Klause solves a MiniZinc model's compiled FlatZinc, so its
     *  solution names FlatZinc variables and gives enums as integers; it goes through the model's output mapping
     *  to the model's own output variables (`stats.finalWitnessLevel=model`). One that cannot be mapped keeps its
     *  FlatZinc-level text (`flatzinc`), with why under `stats.finalWitnessMappingError`. */
    private fun witness(
        ref: ProblemRef,
        raw: String,
        modelOutput: (ProblemRef, String) -> String,
    ): Pair<String?, Map<String, String>> {
        if (ref.format != Format.MINIZINC) {
            return SolveEvidence.finalWitness(ref.format, raw) to emptyMap()
        }
        return runCatching {
            val witness = SolveEvidence.finalWitness(ref.format, raw) { modelOutput(ref, it) }
            witness to mapOf("finalWitnessLevel" to "model")
        }.getOrElse { failure ->
            val reason = (failure.message ?: failure::class.simpleName.orEmpty()).take(REASON_CAP)
            SolveEvidence.finalWitness(ref.format, raw) to
                mapOf("finalWitnessLevel" to "flatzinc", "finalWitnessMappingError" to reason)
        }
    }

    private fun record(
        entry: ResolvedProblem,
        s: SolverInvocation.Settings,
        budget: Budget,
        kind: String,
        timestamp: String,
        sha: String?,
        r: SolverInvocation.Result,
    ): SolveRecord {
        val (firstFeasibleMs, bestMs) = timings(r, entry.maximize)
        val (processFirstMs, processBestMs) = processTimings(r, entry.maximize)
        return SolveRecord(
            problem = entry.name,
            solver = SolverInvocation.KLAUSE,
            engine = s.engine,
            processors = s.processors ?: 1,
            seed = s.seed,
            budgetMs = budget.timeoutMillis,
            kind = kind,
            maximize = entry.maximize,
            feasible = r.feasible,
            objective = r.objective,
            exactObjective = r.exactObjective,
            timeToBestMs = bestMs,
            timeToFirstFeasibleMs = firstFeasibleMs,
            processTimeToFirstFeasibleMs = processFirstMs,
            processTimeToBestMs = processBestMs,
            elapsedMs = r.elapsedMs,
            proven = r.proven,
            stats = r.stats,
            attribution = r.attribution,
            gitSha = sha,
            timestamp = timestamp,
            command = r.command,
            buildProvenance = r.buildProvenance,
            buildFingerprint = r.buildProvenance?.fingerprint,
            stderr = r.stderr,
            stderrComplete = r.stderrComplete,
        )
    }

    /**
     * Recovers first-feasible and best timings from portfolio attribution. MiniZinc solution
     * separators may be buffered until termination. Attribution is in arrival order: bound
     * publication and emission use separate critical sections, so every entry is compared
     * by value. Single-engine solves use streamed separator timings.
     */
    internal fun timings(r: SolverInvocation.Result, maximize: Boolean): Pair<Long?, Long?> {
        if (r.attribution.isEmpty()) return r.timeToFirstFeasibleMs to r.timeToBestMs
        val firstFeasibleMs = r.attribution.first().elapsedMs
        return firstFeasibleMs to best(r.attribution, maximize).elapsedMs
    }

    // The CLI attribution clock starts after preparation; observing its output includes every subprocess phase.
    internal fun processTimings(r: SolverInvocation.Result, maximize: Boolean): Pair<Long?, Long?> {
        if (r.attribution.isEmpty()) return r.timeToFirstFeasibleMs to r.timeToBestMs
        return r.attribution.mapNotNull { it.processElapsedMs }.minOrNull() to
            best(r.attribution, maximize).processElapsedMs
    }

    /** The best-valued entry in [attribution], direction-aware: arrival order is not improvement order.
     *  Every entry in one run shares the same objective channel ([Attribution.continuousObjective] is a
     *  property of the whole model, not the arm), so once any entry carries one, every entry does —
     *  compare on it (the whole model-oriented value). Otherwise compare on [Attribution.exactObjective]
     *  as a [Long], lossless past 2^53. */
    internal fun best(attribution: List<Attribution>, maximize: Boolean): Attribution {
        val sign = if (maximize) 1 else -1
        return if (attribution.any { it.continuousObjective != null }) {
            attribution.maxByOrNull { sign * (it.continuousObjective ?: 0.0) } ?: attribution.last()
        } else {
            attribution.maxByOrNull { sign * (it.exactObjective?.toLongOrNull() ?: 0L) } ?: attribution.last()
        }
    }

    private fun errorRecord(
        entry: ResolvedProblem,
        s: SolverInvocation.Settings,
        budget: Budget,
        kind: String,
        timestamp: String,
        sha: String?,
    ): SolveRecord = SolveRecord(
        problem = entry.name,
        solver = SolverInvocation.KLAUSE,
        engine = s.engine,
        processors = s.processors ?: 1,
        seed = s.seed,
        budgetMs = budget.timeoutMillis,
        kind = kind,
        maximize = entry.maximize,
        feasible = null,
        objective = null,
        timeToBestMs = null,
        proven = false,
        stats = emptyMap(),
        gitSha = sha,
        timestamp = timestamp,
        command = "ERROR",
    )
}
