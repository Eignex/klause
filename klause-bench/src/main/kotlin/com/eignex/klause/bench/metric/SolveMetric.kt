package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.tools.ProfileConfig
import com.eignex.klause.bench.tools.Profiler
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
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
    // fixed | cp | mixed | ls | cp-single — forwarded verbatim to klause-cli `-e` (the cli owns the
    // engine model). Default `fixed` mirrors the cli (annotation-following, the FD default).
    val engine: String = "fixed",
    // null = unset: no `-p` is passed, so the solver applies its own default (klause-cli's is
    // single-core). Multi-thread tracks (parallel/open) must set `processors=` explicitly.
    val processors: Int? = null,
    /** References only (`-f` to `minizinc`): false ⇒ free search, true ⇒ follow the annotation. For
     *  klause the engine value carries free/fixed, so this is ignored. */
    val fixed: Boolean = false,
    /** Repeatable klause-cli `--param key=value` engine knobs (e.g. `var-selector=vsids`); the way
     *  to A/B a heuristic — run `solve` twice with different params and diff the two config dirs. */
    val params: List<String> = emptyList(),
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
    ) {
        if (profile != null) {
            profileEngine(entries, budget, solverId, search, profile)
            return
        }
        val settings = SolverInvocation.Settings(
            engine = if (solverId == SolverInvocation.KLAUSE) search.engine else null,
            processors = search.processors,
            free = !search.fixed,
            seed = SOLVE_SEED,
            params = if (solverId == SolverInvocation.KLAUSE) search.params else emptyList(),
        )
        val tag = configTag(solverId, settings, budget)
        val outDir = File("output", tag).apply { mkdirs() }
        val timestamp = Instant.now().toString()
        val sha = Reports.readGitSha()
        println()
        println("=== solve ($tag; ${budget.timeoutMillis}ms budget) -> output/$tag/ ===")
        var feasible = 0
        var proved = 0
        for (entry in entries) {
            val optimize = entry.objective != null
            val kind = if (optimize) "optimize" else "satisfy"
            val rec = runCatching {
                val key = BenchCache.keyFor(entry, tag, budget)
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
            if (rec.feasible == true) feasible++
            if (rec.proven) proved++
            val mark = if (rec.feasible == null && !rec.proven) "??" else "ok"
            println("$mark [${rec.problem}] $kind = ${display(rec)}")
        }
        println("\n$feasible/${entries.size} feasible, $proved proved  (output/$tag/)")
    }

    /** Filesystem-safe, self-sufficient config identifier: solver + engine + processors + search mode
     *  + budget + any `--param` knobs. Used for BOTH the `output/<config>/` dir name AND the bench
     *  cache key (via [BenchCache.keyFor], which additionally hashes the per-instance model+data), so
     *  a cache hit requires byte-identical settings. Two runs differing in any of these get distinct
     *  dirs/keys (so `param=var-selector=vsids` and `param=var-selector=chb` never clobber). */
    private fun configTag(solverId: String, s: SolverInvocation.Settings, budget: Budget): String = buildString {
        append(solverId)
        s.engine?.let { append('-').append(it) }
        append("-p").append(s.processors ?: 1) // unset ⇒ the solver default (single-core)
        // free/fixed only for references (-f); for klause the engine value already carries it.
        if (s.engine == null) append(if (s.free) "-free" else "-fixed")
        append("-t").append(budget.timeoutMillis / 1000).append('s')
        if (s.params.isNotEmpty()) {
            append('-').append(s.params.joinToString("_") { it.replace('=', '-') })
        }
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
        feasible = r.feasible,
        objective = r.objective,
        timeToBestMs = r.timeToBestMs,
        proven = r.proven,
        stats = r.stats,
        attribution = r.attribution,
        gitSha = sha,
        timestamp = timestamp,
        command = r.command,
    )

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
        if (search.engine !in setOf("cp", "cp-single", "fixed", "ls", "ls-single")) {
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
     *  on the model's annotated search; `cp`/`cp-single` → conflict-driven backtrack. */
    private fun solveInProcess(entry: ResolvedProblem, search: KlauseSearch, cancel: Cancellation) {
        when (search.engine) {
            "ls", "ls-single" -> LocalSearchSolver(entry.problem).solve(
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
