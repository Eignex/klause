package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.profile.SourcePreparationCapture
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.runner.MiniZincCompileFailure
import com.eignex.klause.bench.runner.MiniZincCompilerIdentity
import com.eignex.klause.bench.runner.MiniZincIdentityEvidence
import com.eignex.klause.bench.runner.MiniZincIdentityFailure
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.ProblemKind
import com.eignex.klause.formats.flatzinc.UnsupportedFlatZincException
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import com.eignex.klause.formats.xcsp3.UnsupportedXcsp3Exception
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant

// The durable per-problem record the lab reads.
@Serializable
internal data class SolveRecord(
    val problem: String,
    val solver: String, // always klause; lab reference records share the shape with their own solver id
    val engine: String?, // klause engine (cp/ls/mixed/…); null = the cli's default
    val processors: Int,
    val seed: Long,
    val budgetMs: Long,
    val kind: String, // "optimize" | "satisfy"
    val maximize: Boolean,
    // false = proved infeasible, null = unknown within budget.
    val feasible: Boolean?,
    val objective: Double?,
    // To the best incumbent when optimizing, to the first solution when satisfying.
    val timeToBestMs: Long?,
    val timeToFirstFeasibleMs: Long? = null,
    // Optimum proved, or search closed infeasible or exhausted.
    val proven: Boolean,
    val stats: Map<String, String> = emptyMap(),
    // Arrival order; empty for single-engine solves.
    val attribution: List<Attribution> = emptyList(),
    val gitSha: String?,
    val timestamp: String,
    val command: String,
    val buildProvenance: BuildProvenance? = null,
    val buildFingerprint: String? = null,
    val validationPolicy: String = REPORTED_RESULT_POLICY,
    val elapsedMs: Long? = null,
    // Hashes of the plain source bytes, independent of cache compression.
    val sourceHashes: Map<String, String> = emptyMap(),
    // Retained for independent checks when the raw stream is not transferred.
    val finalWitness: String? = null,
    val processTimeToFirstFeasibleMs: Long? = null,
    val processTimeToBestMs: Long? = null,
    val exactObjective: String? = null,
    val compilerIdentity: MiniZincCompilerIdentity? = null,
    // Failed discovery evidence; never an accepted compiler identity.
    val compilerDiscovery: MiniZincIdentityEvidence? = null,
    // Normalized, limited to 16 Ki characters.
    val stderr: String? = null,
    val stderrComplete: Boolean = false,
    val stderrCapture: StderrCaptureStatus? = null,
    // Bytes supplied to the CLI and output renderer; empty for uncompiled inputs.
    val compiledInputHashes: Map<String, String> = emptyMap(),
    val comparisonControls: Map<String, String> = emptyMap(),
    // Declared and effective MPS policy as emitted by the installed CLI.
    val numericalSemantics: JsonObject? = null,
    // Declared and effective MPS optimality gap as emitted by the installed CLI.
    val optimalityTolerance: JsonObject? = null,
    // Proved original-unit bound of an optimum that holds only within optimalityTolerance of exactObjective: lower
    // when minimizing, upper when maximizing. Null for an exact optimum or no optimum.
    val optimalityBound: String? = null,
)

internal object SolveMetric {
    private const val REASON_CAP = 400
    private const val MAX_LOAD_CAUSE_DEPTH = 64

    // A problem that fails to load gets a record too: `stats.unsupported` when klause declines the model, else
    // `stats.loadError`.
    fun solveOne(
        ref: ProblemRef,
        budget: Budget,
        settings: SolverInvocation.Settings,
        outDir: File,
        timings: HarnessTimings = HarnessTimings(),
    ): SolveRecord {
        val started = timings.start()
        timings.measure("harnessCliPreflightMs") {
            SourcePreparationCapture.measure("cli-preflight") { SolverInvocation.klauseCliDefect() }
        }
            ?.let { error("the klause dist cannot solve: $it") }
        outDir.mkdirs()
        val timestamp = Instant.now().toString()
        val sha = timings.measure("harnessGitIdentityMs") { Reports.readGitSha() }
        val name = Reports.fileStem(ref.name)
        val (rec, raw) = runCatching {
            timings.measure("harnessModelResolveMs") {
                SourcePreparationCapture.measure("model-resolve") { Runners.resolve(ref, settings.exact) }
            }
        }.fold(
            { entry ->
                if (ref.format == Format.MINIZINC) {
                    settings.captureDirectory?.let { directory ->
                        directory.mkdirs()
                        val runner = MiniZincRunner()
                        timings.measure("harnessFznCaptureMs") {
                            runner.compileFzn(ref).copyTo(directory.resolve("model.fzn"), overwrite = true)
                        }
                        timings.measure("harnessOutputCaptureMs") {
                            runner.compileOutput(ref).copyTo(directory.resolve("model.ozn"), overwrite = true)
                        }
                    }
                }
                solve(entry, settings, budget, timestamp, sha, timings = timings)
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
                timings.measure("harnessLoadFailureMs") {
                    loadFailureRecord(ref, settings, budget, timestamp, sha, failure)
                } to null
            },
        )
        raw?.let { File(outDir, "$name.out").writeText(it) }
        // Excludes serialization and writing of the timing record itself.
        timings.finish("harnessSolveOneMs", started)
        val retained = rec.copy(stats = rec.stats + timings.stats())
        File(outDir, "$name.json").writeText(Reports.json.encodeToString(retained))
        return retained
    }

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
            compilerDiscovery = (failure as? MiniZincIdentityFailure)?.evidence,
            gitSha = sha,
            timestamp = timestamp,
            command = "LOAD",
        )
    }

    // Declined a model it read, as opposed to failing to compile or parse it.
    private fun declined(failure: Throwable): Boolean = generateSequence(failure) { it.cause }
        .take(MAX_LOAD_CAUSE_DEPTH).any {
            it is UnsupportedFlatZincException || it is UnsupportedXcsp3Exception || it is UnsupportedSmtException
        }

    // A solve that throws becomes an error record.
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
        timings: HarnessTimings = HarnessTimings(),
    ): Pair<SolveRecord, String?> {
        val started = timings.start()
        val optimize = timings.measure("harnessObjectiveMs") {
            SourcePreparationCapture.measure("model-objective") { entry.optimizes }
        }
        val kind = if (optimize) "optimize" else "satisfy"
        var sourceHashes: Map<String, String> = emptyMap()
        var provenance: BuildProvenance? = null
        var compiler: MiniZincCompilerIdentity? = null
        val result = runCatching {
            sourceHashes = timings.measure("harnessSourceHashMs") {
                SourcePreparationCapture.measure("source-hash") { SolveEvidence.sourceHashes(entry.ref) }
            }
            provenance = timings.measure("harnessBuildProvenanceMs") {
                SourcePreparationCapture.measure("build-provenance") { installedBuild() }
            }
            compiler = if (entry.ref.format == Format.MINIZINC) {
                timings.measure("harnessCompilerIdentityMs") { MiniZincCompatibility.compiler }
            } else {
                null
            }
            val r = timings.measure("harnessInvocationMs") { invoke(entry, settings, budget, optimize) }
            val unavailable = when {
                r.stats["smtModelRequest"] == "unsupported" -> r.stats["smtModelRequestReason"]
                entry.ref.format == Format.SMTLIB && (r.stats["smtQueryResponses"]?.toIntOrNull() ?: 0) > 1 ->
                    "multiple SMT queries require sequence-aware source validation"
                else -> null
            }
            val (witness, witnessStats) = if (unavailable != null) {
                null to mapOf("finalWitnessUnavailable" to unavailable)
            } else if (r.feasible == true) {
                timings.measure("harnessWitnessMs") {
                    witness(entry.ref, r.rawOutput) { ref, raw ->
                        timings.measure("harnessOutputMappingMs") { modelOutput(ref, raw) }
                    }
                }
            } else {
                null to emptyMap()
            }
            record(entry, settings, budget, kind, timestamp, sha, r).let { reported ->
                reported.copy(
                    stats = reported.stats + witnessStats,
                    compilerIdentity = compiler,
                    sourceHashes = sourceHashes,
                    finalWitness = witness,
                )
            } to r.rawOutput
        }.getOrElse { failure ->
            val invocation = (failure as? SolverInvocation.Failure)?.result
            val failed = if (failure is MiniZincIdentityFailure) {
                loadFailureRecord(entry.ref, settings, budget, timestamp, sha, failure, sourceHashes)
            } else {
                invocation?.let { record(entry, settings, budget, kind, timestamp, sha, it) }
                    ?: errorRecord(entry, settings, budget, kind, timestamp, sha)
            }
            val record = failed
                .copy(
                    stats = failed.stats + invocation?.stats.orEmpty() + SolveFailure.stats(failure),
                    compilerIdentity = compiler.takeUnless { failure is MiniZincIdentityFailure },
                    sourceHashes = sourceHashes,
                    buildProvenance = provenance,
                    buildFingerprint = provenance?.fingerprint,
                )
            println("?? [${entry.name}] $kind ERROR: ${record.stats["errorMessage"] ?: record.stats["errorClass"]}")
            record to invocation?.rawOutput
        }
        timings.finish("harnessSolveMs", started)
        return result.first.copy(stats = result.first.stats + timings.stats()) to result.second
    }

    // Klause solves the compiled FlatZinc, so its witness names FlatZinc variables and gives enums as integers; the
    // output mapping turns it into the model's output (`finalWitnessLevel=model`). An unmappable witness keeps its
    // FlatZinc text, with why under `stats.finalWitnessMappingError`.
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
            numericalSemantics = r.stats["numericalSemantics"]?.let {
                runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            },
            optimalityTolerance = r.stats["optimalityTolerance"]?.let {
                runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            },
            optimalityBound = r.stats["objectiveBound"]
                ?.takeIf { r.proven && r.stats["optimizationStatus"] == "gap-optimum" },
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
            stderrCapture = r.stderrCapture,
            compiledInputHashes = r.compiledInputHashes,
        )
    }

    // MiniZinc solution separators may be buffered until termination, so portfolio timings come from attribution.
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

    // Arrival order is not improvement order. continuousObjective is a property of the whole model, so once any
    // entry carries one every entry does; otherwise compare exact rationals, including values beyond Long.
    internal fun best(attribution: List<Attribution>, maximize: Boolean): Attribution {
        val comparator = if (attribution.any { it.continuousObjective != null }) {
            compareBy<Attribution> { it.continuousObjective ?: 0.0 }
        } else {
            compareBy<Attribution> { it.exactObjective?.let(ExactObjective::parse) }
        }
        return attribution.maxWithOrNull(if (maximize) comparator else comparator.reversed()) ?: attribution.last()
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
