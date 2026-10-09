package com.eignex.klause.bench.metric

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.runner.Runners
import com.eignex.klause.bench.source.ProblemKind
import com.eignex.klause.bench.tools.ProfileConfig
import com.eignex.klause.bench.tools.Profiler
import com.eignex.klause.formats.flatzinc.UnsupportedFlatZincException
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import com.eignex.klause.formats.xcsp3.UnsupportedXcsp3Exception
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant

/**
 * Single-solver solve over a corpus, run entirely as **subprocesses** (see [SolverInvocation]):
 * klause via `klause-cli`, references via `minizinc --solver <id>`.
 *
 * Output is saved **one file per problem**, under `output/<config>/`, where `<config>` encodes the
 * solver + its settings + the time budget (so multiple settings-runs coexist without clobbering —
 * see [configTag]). Each problem yields two files:
 *  - `<problem>.out` — the raw, verbatim solver stdout (the MiniZinc-format stream); this **is** the
 *    run's log.
 *  - `<problem>.json` — a self-describing [SolveRecord]: solver, settings, problem, budget, plus the
 *    parsed result (objective, time-to-best, proof/feasibility, `%%%mzn-stat` statistics, the exact
 *    command, git sha, timestamp).
 *
 * No in-session comparison: to compare two configs, run this once per config (each writes its own
 * `output/<config>/` dir) and diff offline (`output/compare.sh`). One solver's crash never taints
 * another's. When [ProfileConfig] is set, the run instead profiles the klause engine in-process.
 */
internal data class KlauseSearch(
    // fixed | cp | mixed | ls — forwarded verbatim to klause-cli `-e` (the cli owns the
    // engine model). null = unset: no `-e` is passed, so the bench follows the cli's own default
    // engine (the bench deliberately has no engine default of its own).
    val engine: String? = null,
    // null = unset: no `-p` is passed, so the solver applies its own default (klause-cli's is
    // single-core). Multi-thread tracks (parallel/open) must set `processors=` explicitly.
    val processors: Int? = null,
    /** References only (`-f` to `minizinc`): false ⇒ free search, true ⇒ follow the annotation. For
     *  klause the engine value carries free/fixed, so this is ignored. */
    val fixed: Boolean = false,
    /** Repeatable klause-cli `--param key=value` engine knobs (e.g. `var-selector=vsids`); the way
     *  to A/B a heuristic — run `solve` twice with different params and diff the two config dirs. */
    val params: List<String> = emptyList(),
    /** klause-cli `--lp CEILING`: the LP-relaxation emphasis (`off`|`conservative`|`default`|
     *  `aggressive`, plus `+id`/`-id` per-technique deltas). null = unset (cli's own default). */
    val lp: String? = null,
    /** klause-cli `--presolve`: the presolve emphasis plus `+id`/`-id` per-pass deltas (e.g.
     *  `default,+lp-harvest`). null = unset (cli's own default). */
    val presolve: String? = null,
    /** The solver's random seed (`-r` / `--random-seed`); null = the bench's fixed seed, so repeated runs of
     *  one config are comparable unless seeds are swept on purpose. */
    val seed: Long? = null,
    val exact: Boolean = false,
)

/** One problem's result for one solver+settings+budget — the durable per-problem record. */
@Serializable
internal data class SolveRecord(
    val problem: String,
    val solver: String, // solver id (klause | choco | gecode | yuck | …)
    val engine: String?, // klause engine (cp/ls/portfolio); null for references
    val processors: Int,
    val search: String, // "free" | "fixed"
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
    /** ms to the first feasible solution; null when never feasible. Drives the feasibility-speed
     *  calibration lens (a fast-feasible specialist scores here even when another arm holds a better
     *  final objective). */
    val timeToFirstFeasibleMs: Long? = null,
    /** optimum proved (optimize) or search closed UNSAT/exhausted. */
    val proven: Boolean,
    val stats: Map<String, String> = emptyMap(),
    /** Per-arm improvement stream from a klause portfolio `-s` run (`%%%klause-arm:` lines), in
     *  arrival order; empty for references and single-engine klause. Each current record preserves
     *  the exact discrete objective as decimal text and, when needed, the whole continuous objective.
     *  The legacy numeric [Attribution.objective] remains readable in persisted records. */
    val attribution: List<Attribution> = emptyList(),
    val gitSha: String?,
    val timestamp: String,
    val command: String,
    val buildProvenance: BuildProvenance? = null,
    val buildFingerprint: String? = null,
    val validationPolicy: String = REPORTED_RESULT_POLICY,
    /** Subprocess duration, separate from incumbent timings; null when unavailable or in legacy records. */
    val elapsedMs: Long? = null,
    /** Plain source bytes, independent of cache compression; absent on legacy or failed loads. */
    val sourceHashes: Map<String, String> = emptyMap(),
    /** Final rendered candidate, retained for independent checks when the raw stream is not transferred. */
    val finalWitness: String? = null,
)

internal object SolveMetric {
    private const val SOLVE_SEED = 3L

    /** The `backend=` that runs each format's reference solver instead of one fixed solver. */
    const val REFERENCE = "reference"

    /** The MiniZinc solver `backend=reference` uses, as `bench reference` defaults to. */
    private const val REFERENCE_MINIZINC_BACKEND = "cp-sat"

    /** Characters of a load failure's message kept in its record. */
    private const val REASON_CAP = 400

    /** Run [solverId] (`"klause"` or a registered MiniZinc reference id) over [entries], saving one
     *  `.out` + `.json` per problem under `output/<config>/`. [search] applies to klause; references
     *  take only processors + free. When [profile] is set, profiles the klause engine in-process
     *  instead (subprocess solves can't be JFR-sampled from the bench JVM). */
    fun run(
        entries: Sequence<ResolvedProblem>,
        budget: Budget = Budget(),
        solverId: String = SolverInvocation.KLAUSE,
        search: KlauseSearch = KlauseSearch(),
        profile: ProfileConfig? = null,
        label: String? = null,
    ): File? {
        if (profile != null) {
            profileEngine(entries.toList(), budget, solverId, search, profile)
            return null
        }
        val settings = settings(solverId, search)
        if (solverId == SolverInvocation.KLAUSE) {
            SolverInvocation.klauseCliDefect()?.let { error("the klause dist cannot solve: $it") }
        }
        val tag = configTag(solverId, settings, budget, label)
        val outDir = File("output", tag).apply { mkdirs() }
        val timestamp = Instant.now().toString()
        val sha = Reports.readGitSha()
        println()
        println("=== solve ($tag; ${budget.timeoutMillis}ms budget) -> output/$tag/ ===")
        var feasible = 0
        var proved = 0
        // Feature columns (structure/format/…) are joined onto each result row from the committed
        // oracle table, so `output/<tag>.csv` is analysable by structure/size out of the box.
        val features = ReferenceStore.load()
        val resultRows = ArrayList<ReferenceEntry>()
        for (entry in entries) {
            val (rec, raw) = solve(entry, solverId, settings, budget, tag, timestamp, sha)
            raw?.let { File(outDir, flat(entry) + ".out").writeText(it) }
            File(outDir, flat(entry) + ".json").writeText(Reports.json.encodeToString(rec))
            val suite = ReferenceStore.suiteOf(entry.ref)
            resultRows += resultRow(suite, rec, tag, features[suite to rec.problem])
            if (rec.feasible == true) feasible++
            if (rec.proven) proved++
            val mark = if (rec.feasible == null && !rec.proven) "??" else "ok"
            println("$mark [${rec.problem}] ${rec.kind} = ${display(rec)}")
        }
        // A per-run result table in the reference-table schema (solver = this config's tag) — the input
        // `bench credit` compares, keyed by (suite, problem), sliceable by the joined feature columns.
        ReferenceStore.writeCsv(File("output", "$tag.csv"), resultRows)
        println("\n$feasible/${resultRows.size} feasible, $proved proved  (output/$tag/, output/$tag.csv)")
        return outDir
    }

    /** Solve the one problem [ref] as `solve` would, writing its [SolveRecord] to `<outDir>/<problem>.json`
     *  and the raw solver output beside it, and return the record. Unlike [run] it writes no per-run table and
     *  loads no reference tables, so one process per problem stays cheap; the caller gathers the records. A problem
     *  that fails to load gets a record too, its reason under `stats.unsupported` when klause declines the model
     *  and under `stats.loadError` otherwise, where `run` skips it: one problem per process has nothing to skip to. */
    fun solveOne(
        ref: ProblemRef,
        budget: Budget = Budget(),
        solverId: String = SolverInvocation.KLAUSE,
        search: KlauseSearch = KlauseSearch(),
        label: String? = null,
        outDir: File? = null,
        /** For a reference solve, which of the format's reference solvers runs; null for its default. */
        referenceSolver: String? = null,
    ): SolveRecord {
        val settings = settings(solverId, search)
        if (solverId == SolverInvocation.KLAUSE) {
            SolverInvocation.klauseCliDefect()?.let { error("the klause dist cannot solve: $it") }
        }
        val tag = configTag(solverId, settings, budget, label)
        val dir = (outDir ?: File("output", tag)).apply { mkdirs() }
        val timestamp = Instant.now().toString()
        val sha = Reports.readGitSha()
        val name = ref.name.replace('/', '_')
        val (rec, raw) = if (solverId == REFERENCE) {
            referenceRecord(ref, settings, budget, timestamp, sha, referenceSolver, File(dir, "$name.sol"))
        } else {
            runCatching { Runners.resolve(ref, settings.exact) }.fold(
                { entry -> solve(entry, solverId, settings, budget, tag, timestamp, sha) },
                { failure -> loadFailureRecord(ref, solverId, settings, budget, timestamp, sha, failure) to null },
            )
        }
        raw?.let { File(dir, "$name.out").writeText(it) }
        File(dir, "$name.json").writeText(Reports.json.encodeToString(rec))
        return rec
    }

    /**
     * The format's reference solver on [ref] (see [ReferenceSolve]), as a record whose `solver` names the solver that
     * ran. The instance goes to that solver as it is, without klause's front end, so a model klause declines still
     * gets a reference verdict. A run that fails is an error record. A solution the reference returned is written to
     * [solution], as `name value` lines, so its verdict can be checked again.
     */
    private fun referenceRecord(
        ref: ProblemRef,
        s: SolverInvocation.Settings,
        budget: Budget,
        timestamp: String,
        sha: String?,
        solver: String?,
        solution: File,
    ): Pair<SolveRecord, String?> = runCatching {
        val run = ReferenceSolve.run(ref, REFERENCE_MINIZINC_BACKEND, s, budget, solver)
        val r = run.result
        r.assignment?.let { solution.writeText(it) }
        SolveRecord(
            problem = ref.name,
            solver = run.solver,
            engine = null,
            processors = s.processors ?: 1,
            search = if (s.free) "free" else "fixed",
            seed = s.seed,
            budgetMs = budget.timeoutMillis,
            kind = if (run.optimize) "optimize" else "satisfy",
            maximize = run.maximize,
            feasible = r.feasible,
            objective = r.objective,
            timeToBestMs = r.timeToBestMs,
            timeToFirstFeasibleMs = r.timeToFirstFeasibleMs,
            elapsedMs = r.elapsedMs,
            proven = r.proven,
            stats = r.stats,
            gitSha = sha,
            timestamp = timestamp,
            command = r.command,
            buildProvenance = r.buildProvenance,
            buildFingerprint = r.buildProvenance?.fingerprint,
        ) to r.rawOutput
    }.getOrElse { failure ->
        println("?? [${ref.name}] reference ERROR: ${failure.message ?: failure::class.simpleName}")
        loadFailureRecord(
            ref,
            solver ?: ReferenceSolve.solverIdFor(ref, REFERENCE_MINIZINC_BACKEND),
            s,
            budget,
            timestamp,
            sha,
            failure,
        )
            .copy(command = "ERROR") to null
    }

    /** The record of a problem that never reached the solver: undecided, with why under `stats`. */
    internal fun loadFailureRecord(
        ref: ProblemRef,
        solverId: String,
        s: SolverInvocation.Settings,
        budget: Budget,
        timestamp: String,
        sha: String?,
        failure: Throwable,
    ): SolveRecord {
        val reason = (failure.message ?: failure::class.simpleName.orEmpty()).lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ").take(REASON_CAP)
        return SolveRecord(
            problem = ref.name,
            solver = solverId,
            engine = s.engine,
            processors = s.processors ?: 1,
            search = if (s.free) "free" else "fixed",
            seed = s.seed,
            budgetMs = budget.timeoutMillis,
            kind = if (runCatching { ProblemKind.isCop(ref) }.getOrDefault(false)) "optimize" else "satisfy",
            maximize = false,
            feasible = null,
            objective = null,
            timeToBestMs = null,
            proven = false,
            stats = mapOf((if (declined(failure)) "unsupported" else "loadError") to reason),
            gitSha = sha,
            timestamp = timestamp,
            command = "LOAD",
        )
    }

    /** Whether klause declined a model it read, rather than the model failing to compile or parse. */
    private fun declined(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.any {
        it is UnsupportedFlatZincException || it is UnsupportedXcsp3Exception || it is UnsupportedSmtException
    }

    private fun settings(solverId: String, search: KlauseSearch) = SolverInvocation.Settings(
        engine = if (solverId == SolverInvocation.KLAUSE) search.engine else null,
        processors = search.processors,
        free = !search.fixed,
        seed = search.seed ?: SOLVE_SEED,
        params = if (solverId == SolverInvocation.KLAUSE) search.params else emptyList(),
        lp = if (solverId == SolverInvocation.KLAUSE) search.lp else null,
        presolve = if (solverId == SolverInvocation.KLAUSE) search.presolve else null,
        exact = solverId == SolverInvocation.KLAUSE && search.exact,
    )

    /** One problem's record, from the cache or a fresh subprocess solve, with the raw solver output when the
     *  solve ran; a solve that throws becomes an error record. */
    private fun solve(
        entry: ResolvedProblem,
        solverId: String,
        settings: SolverInvocation.Settings,
        budget: Budget,
        tag: String,
        timestamp: String,
        sha: String?,
    ): Pair<SolveRecord, String?> {
        val optimize = entry.objective != null
        val kind = if (optimize) "optimize" else "satisfy"
        return runCatching {
            val sourceParams = SourceValidationParams(settings)
            val checkSource = solverId == SolverInvocation.KLAUSE && (entry.hasFloats || sourceParams.requested)
            require(!sourceParams.requested || entry.ref.format == Format.MINIZINC) {
                "source-validation requires a MiniZinc source"
            }
            val policy = if (checkSource) {
                PINNED_SOURCE_POLICY
            } else {
                REPORTED_RESULT_POLICY
            }
            val provenance = if (solverId == SolverInvocation.KLAUSE) InstalledBuild.current else null
            val key = BenchCache.keyFor(entry.ref, tag, budget, provenance, settings, policy)
            val r = BenchCache.load(key)
                ?: SolverInvocation.run(entry, solverId, sourceParams.solverSettings, budget, optimize)
                    .also { BenchCache.store(key, it) }
            val reported = record(entry, solverId, settings, budget, kind, timestamp, sha, r)
                .copy(
                    validationPolicy = policy,
                    sourceHashes = SolveEvidence.sourceHashes(entry.ref),
                    finalWitness = if (r.feasible == true) {
                        SolveEvidence.finalWitness(entry.ref.format, r.rawOutput)
                    } else {
                        null
                    },
                )
            val checked = if (checkSource) {
                val validation = if (r.feasible == true) {
                    MiniZincSourceValidation.validate(entry.ref, r.rawOutput, r.objective)
                } else {
                    SourceValidation("unknown", "no feasible candidate to check")
                }
                reported.sourceChecked(validation, entry.floatApproximation)
            } else {
                reported
            }
            checked to r.rawOutput
        }.getOrElse {
            println("?? [${entry.name}] $kind ERROR: ${it.message ?: it::class.simpleName}")
            errorRecord(entry, solverId, settings, budget, kind, timestamp, sha) to null
        }
    }

    /** This run's result for one instance as a reference-table-schema row: `solver` is the config [tag],
     *  `elapsedMs` the time-used proxy (incumbent, solve time, or wall clock when decided; budget otherwise),
     *  matching the `compare.sh` convention, and the source-text features are joined from the committed table
     *  ([ref], null when the instance has no oracle entry). */
    internal fun resultRow(suite: String, rec: SolveRecord, tag: String, ref: ReferenceEntry?): ReferenceEntry {
        val solved = rec.feasible != null
        val elapsed = if (solved) {
            rec.timeToBestMs ?: solveTimeMs(rec.stats) ?: rec.elapsedMs ?: rec.budgetMs
        } else {
            rec.budgetMs
        }
        return ReferenceEntry(
            suite = suite,
            problem = rec.problem,
            maximize = rec.maximize,
            objective = rec.objective,
            feasible = rec.feasible,
            proven = rec.proven,
            elapsedMs = elapsed,
            solver = tag,
            budgetMs = rec.budgetMs,
            format = ref?.format.orEmpty(),
            structure = ref?.structure.orEmpty(),
            numGlobal = ref?.numGlobal,
            numLinear = ref?.numLinear,
            boolHeavy = ref?.boolHeavy,
        )
    }

    /** Filesystem-safe, self-sufficient config identifier: solver + engine + processors + search mode
     *  + budget + any `--param` knobs. Used for BOTH the `output/<config>/` dir name AND the bench
     *  cache key (via [BenchCache.keyFor], which additionally hashes the per-instance model+data), so
     *  a cache hit requires byte-identical settings. Two runs differing in any of these get distinct
     *  dirs/keys (so `param=var-selector=vsids` and `param=var-selector=chb` never clobber). */
    internal fun configTag(
        solverId: String,
        s: SolverInvocation.Settings,
        budget: Budget,
        label: String? = null,
    ): String = buildString {
        append(solverId)
        s.engine?.let { append('-').append(it) }
        if (s.exact) append("-exact")
        append("-p").append(s.processors ?: 1) // unset ⇒ the solver default (single-core)
        // free/fixed only for references (their `-f` toggle); klause carries it in the engine value,
        // and a null klause engine just means "the cli's default engine" (no suffix).
        if (s.engine == null && solverId != SolverInvocation.KLAUSE) append(if (s.free) "-free" else "-fixed")
        append("-t").append(budget.timeoutMillis / 1000).append('s')
        if (s.seed != SOLVE_SEED) append("-r").append(s.seed)
        s.lp?.let { append("-lp-").append(it.replace(Regex("[^A-Za-z0-9.+-]"), "")) }
        s.presolve?.let { append("-ps-").append(it.replace(Regex("[^A-Za-z0-9.+-]"), "")) }
        if (s.params.isNotEmpty()) {
            // Filesystem-safe: '=' → '-', then any other unsafe char (e.g. '/' in an arm label like
            // `cbls/fixed`) → '_', so a param value never spills into a subdirectory.
            append(
                '-',
            ).append(s.params.joinToString("_") { it.replace('=', '-').replace(Regex("[^A-Za-z0-9._-]"), "_") })
        }
        // Free-form run [label] (e.g. a klause version / fix name) so re-runs of the same config
        // coexist as distinct dirs+cache namespaces instead of overwriting. Filesystem-sanitised.
        label?.trim()?.takeIf { it.isNotEmpty() }?.let { append('-').append(it.replace(Regex("[^A-Za-z0-9._-]"), "-")) }
    }

    private fun flat(entry: ResolvedProblem): String = entry.name.replace('/', '_')

    private fun record(
        entry: ResolvedProblem,
        solverId: String,
        s: SolverInvocation.Settings,
        budget: Budget,
        kind: String,
        timestamp: String,
        sha: String?,
        r: SolverInvocation.Result,
    ): SolveRecord {
        val (firstFeasibleMs, bestMs) = timings(r, entry.maximize)
        return SolveRecord(
            problem = entry.name,
            solver = solverId,
            engine = s.engine,
            processors = s.processors ?: 1,
            search = if (s.free) "free" else "fixed",
            seed = s.seed,
            budgetMs = budget.timeoutMillis,
            kind = kind,
            maximize = entry.maximize,
            feasible = r.feasible,
            objective = r.objective,
            timeToBestMs = bestMs,
            timeToFirstFeasibleMs = firstFeasibleMs,
            elapsedMs = r.elapsedMs,
            proven = r.proven,
            stats = r.stats,
            attribution = r.attribution,
            gitSha = sha,
            timestamp = timestamp,
            command = r.command,
            buildProvenance = r.buildProvenance,
            buildFingerprint = r.buildProvenance?.fingerprint,
        )
    }

    /**
     * Real (first-feasible, best) timings. klause emits its anytime trajectory as `%%%klause-arm:`
     * lines (parsed into [SolverInvocation.Result.attribution]), but the MiniZinc `----------` stream
     * is flushed once at termination — so the separator-based timings collapse to ~budget. When the
     * attribution stream is present, recover the truth from it: the first incumbent is the first
     * feasible solution and the best-valued incumbent is the time-to-best. The stream is arrival-order,
     * not improvement order — a concurrent portfolio's shared-bound CAS and its attribution-emit lock
     * are separate critical sections (see `Portfolio.fold`), so a worse incumbent's line can print after
     * a better one's under thread scheduling — so this searches every entry by value rather than
     * trusting the last one. Reference solvers carry no attribution, so fall back to their (correctly
     * streamed) separator timings.
     */
    internal fun timings(r: SolverInvocation.Result, maximize: Boolean): Pair<Long?, Long?> {
        if (r.attribution.isEmpty()) return r.timeToFirstFeasibleMs to r.timeToBestMs
        val firstFeasibleMs = r.attribution.first().elapsedMs
        return firstFeasibleMs to best(r.attribution, maximize).elapsedMs
    }

    /** The best-valued entry in [attribution], direction-aware — also the best-holder for per-arm credit
     *  (see [ArmMining]), for the same reason: arrival order is not improvement order.
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
        solverId: String,
        s: SolverInvocation.Settings,
        budget: Budget,
        kind: String,
        timestamp: String,
        sha: String?,
    ): SolveRecord = SolveRecord(
        problem = entry.name,
        solver = solverId,
        engine = s.engine,
        processors = s.processors ?: 1,
        search = if (s.free) "free" else "fixed",
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

    private fun display(rec: SolveRecord): String {
        val at = "@${rec.timeToBestMs ?: "-"}ms"
        if (rec.command == "ERROR") return "ERROR"
        return when {
            rec.kind != "optimize" -> when (rec.feasible) {
                true -> "SAT$at"
                false -> "UNSAT"
                null -> "?"
            }

            rec.objective == null -> "?"

            else -> "${if (rec.proven) "opt" else "best"}=${rec.objective}$at"
        }
    }

    /** Profiling mode: run the klause engine IN-PROCESS under JFR so the profile captures the
     *  actual solver. Only klause + a single engine (`cp` → [BacktrackSolver], `ls` →
     *  [LocalSearchSolver]) is profilable — references are external, and the portfolio mixes
     *  engines. Pair with one (or few) instances at a real `timeout=` for a meaningful sample set. */
    private fun profileEngine(
        entries: List<ResolvedProblem>,
        budget: Budget,
        solverId: String,
        search: KlauseSearch,
        profile: ProfileConfig,
    ) {
        if (solverId != SolverInvocation.KLAUSE) {
            println("profile= profiles the klause engine in-process; '$solverId' is external")
            return
        }
        if (search.engine !in setOf("cp", "fixed", "ls")) {
            println("profile= needs a single-solver engine (not the '${search.engine}' portfolio)")
            return
        }
        println()
        println("=== profiling klause-${search.engine} in-process (${profile.event}); ${entries.size} instance(s) ===")
        Profiler.record(profile) {
            for (entry in entries) {
                val deadline = System.currentTimeMillis() + budget.timeoutMillis
                val cancel = Cancellation { System.currentTimeMillis() > deadline }
                runCatching { solveInProcess(entry, search, cancel) }
            }
        }
    }

    /** A single in-process klause solve for the profiler: `ls` → local search; `fixed` → backtrack
     *  on the model's annotated search; `cp` → conflict-driven backtrack. */
    private fun solveInProcess(entry: ResolvedProblem, search: KlauseSearch, cancel: Cancellation) {
        when (search.engine) {
            "ls" -> LocalSearchSolver(entry.problem.bake()).solve(
                LocalSearchParams(randomSeed = SOLVE_SEED, cancellation = cancel, lsObjective = entry.lsObjective),
            )

            else -> {
                val params = (entry.searchParams?.takeIf { search.engine == "fixed" })?.copy(cancellation = cancel)
                    ?: BacktrackPresets.conflictDriven(randomSeed = SOLVE_SEED, cancellation = cancel)
                val solver = BacktrackSolver(entry.problem.bake())
                entry.objective?.let { solver.minimize(it, params) } ?: solver.solve(params)
            }
        }
    }
}
