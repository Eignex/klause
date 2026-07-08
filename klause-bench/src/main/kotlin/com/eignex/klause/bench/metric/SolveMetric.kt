package com.eignex.klause.bench.metric

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.tools.ProfileConfig
import com.eignex.klause.bench.tools.Profiler
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.solver.Cancellation
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
    /** klause-cli `--lp CEILING`: the LP-relaxation emphasis (`off`|`conservative`|`balanced`|
     *  `aggressive`, plus `+id`/`-id` per-technique deltas). null = unset (cli's own default). */
    val lp: String? = null,
    /** klause-cli `--presolve`: the presolve emphasis plus `+id`/`-id` per-pass deltas (e.g.
     *  `default,+lp-harvest`). null = unset (cli's own default). */
    val presolve: String? = null,
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
     *  arrival order; empty for references and single-engine klause. The `credit.sh` script
     *  aggregates this across a config dir into first/best/sole/marginal per-arm credit. */
    val attribution: List<Attribution> = emptyList(),
    val gitSha: String?,
    val timestamp: String,
    val command: String,
)

internal object SolveMetric {
    private const val SOLVE_SEED = 3L

    /** Run [solverId] (`"klause"` or a registered MiniZinc reference id) over [entries], saving one
     *  `.out` + `.json` per problem under `output/<config>/`. [search] applies to klause; references
     *  take only processors + free. When [profile] is set, profiles the klause engine in-process
     *  instead (subprocess solves can't be JFR-sampled from the bench JVM). */
    fun run(
        entries: List<ResolvedProblem>,
        budget: Budget = Budget(),
        solverId: String = SolverInvocation.KLAUSE,
        search: KlauseSearch = KlauseSearch(),
        profile: ProfileConfig? = null,
        label: String? = null,
    ): File? {
        if (profile != null) {
            profileEngine(entries, budget, solverId, search, profile)
            return null
        }
        val settings = SolverInvocation.Settings(
            engine = if (solverId == SolverInvocation.KLAUSE) search.engine else null,
            processors = search.processors,
            free = !search.fixed,
            seed = SOLVE_SEED,
            params = if (solverId == SolverInvocation.KLAUSE) search.params else emptyList(),
            lp = if (solverId == SolverInvocation.KLAUSE) search.lp else null,
            presolve = if (solverId == SolverInvocation.KLAUSE) search.presolve else null,
        )
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
        val resultRows = ArrayList<ReferenceEntry>(entries.size)
        for (entry in entries) {
            val optimize = entry.objective != null
            val kind = if (optimize) "optimize" else "satisfy"
            val rec = runCatching {
                val key = BenchCache.keyFor(entry.ref, tag, budget)
                val r = BenchCache.load(key)
                    ?: SolverInvocation.run(
                        entry,
                        solverId,
                        settings,
                        budget,
                        optimize,
                    ).also { BenchCache.store(key, it) }
                File(outDir, flat(entry) + ".out").writeText(r.rawOutput)
                record(entry, solverId, settings, budget, kind, timestamp, sha, r)
            }.getOrElse {
                println("?? [${entry.name}] $kind ERROR: ${it.message ?: it::class.simpleName}")
                errorRecord(entry, solverId, settings, budget, kind, timestamp, sha)
            }
            File(outDir, flat(entry) + ".json").writeText(Reports.json.encodeToString(rec))
            val suite = ReferenceStore.suiteOf(entry.ref)
            resultRows += resultRow(suite, rec, tag, features[suite to rec.problem])
            if (rec.feasible == true) feasible++
            if (rec.proven) proved++
            val mark = if (rec.feasible == null && !rec.proven) "??" else "ok"
            println("$mark [${rec.problem}] $kind = ${display(rec)}")
        }
        // A per-run result table in the references.csv schema (solver = this config's tag) — the input
        // `bench credit` compares, keyed by (suite, problem), sliceable by the joined feature columns.
        ReferenceStore.writeCsv(File("output", "$tag.csv"), resultRows)
        println("\n$feasible/${entries.size} feasible, $proved proved  (output/$tag/, output/$tag.csv)")
        return outDir
    }

    /** This run's result for one instance as a references.csv-schema row: `solver` is the config [tag],
     *  `elapsedMs` the time-used proxy (time-to-best when solved, else the budget — matching the
     *  `compare.sh` convention), and the source-text features are joined from the committed table
     *  ([ref], null when the instance has no oracle entry). */
    internal fun resultRow(suite: String, rec: SolveRecord, tag: String, ref: ReferenceEntry?): ReferenceEntry {
        val solved = rec.feasible != null
        val elapsed = if (solved) (rec.timeToBestMs ?: rec.budgetMs) else rec.budgetMs
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
    private fun configTag(
        solverId: String,
        s: SolverInvocation.Settings,
        budget: Budget,
        label: String? = null,
    ): String = buildString {
        append(solverId)
        s.engine?.let { append('-').append(it) }
        append("-p").append(s.processors ?: 1) // unset ⇒ the solver default (single-core)
        // free/fixed only for references (their `-f` toggle); klause carries it in the engine value,
        // and a null klause engine just means "the cli's default engine" (no suffix).
        if (s.engine == null && solverId != SolverInvocation.KLAUSE) append(if (s.free) "-free" else "-fixed")
        append("-t").append(budget.timeoutMillis / 1000).append('s')
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
            proven = r.proven,
            stats = r.stats,
            attribution = r.attribution,
            gitSha = sha,
            timestamp = timestamp,
            command = r.command,
        )
    }

    /**
     * Real (first-feasible, best) timings. klause emits its anytime trajectory as `%%%klause-arm:`
     * lines (parsed into [SolverInvocation.Result.attribution]), but the MiniZinc `----------` stream
     * is flushed once at termination — so the separator-based timings collapse to ~budget. When the
     * attribution stream is present, recover the truth from it: the first incumbent is the first
     * feasible solution, and the earliest best-objective incumbent is the time-to-best. Reference
     * solvers carry no attribution, so fall back to their (correctly streamed) separator timings.
     */
    private fun timings(r: SolverInvocation.Result, maximize: Boolean): Pair<Long?, Long?> {
        if (r.attribution.isEmpty()) return r.timeToFirstFeasibleMs to r.timeToBestMs
        val firstFeasibleMs = r.attribution.first().elapsedMs
        val objectives = r.attribution.mapNotNull { it.objective }
        val best = objectives.maxByOrNull { if (maximize) it else -it }
        val bestMs = best?.let { b -> r.attribution.first { it.objective == b }.elapsedMs }
            ?: r.attribution.last().elapsedMs
        return firstFeasibleMs to bestMs
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
            "ls" -> LocalSearchSolver(entry.problem).solve(
                LocalSearchParams(randomSeed = SOLVE_SEED, cancellation = cancel, lsObjective = entry.lsObjective),
            )

            else -> {
                val params = (entry.searchParams?.takeIf { search.engine == "fixed" })?.copy(cancellation = cancel)
                    ?: BacktrackPresets.conflictDriven(randomSeed = SOLVE_SEED, cancellation = cancel)
                val solver = BacktrackSolver(entry.problem)
                entry.objective?.let { solver.minimize(it, params) } ?: solver.solve(params)
            }
        }
    }
}
