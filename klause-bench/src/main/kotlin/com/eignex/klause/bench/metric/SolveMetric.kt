package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
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
import java.io.File
import java.time.Instant

/**
 * Single-solver solve over a corpus, run entirely as **subprocesses** (see [SolverInvocation]):
 * klause via `klause-cli`, references via `minizinc --solver <id>`. One [run] measures one solver
 * and records, per instance, the objective reached + time-to-best (optimization) or feasibility
 * (satisfaction), whether it was proved, and the solver's `%%%mzn-stat` statistics. Each instance's
 * raw MiniZinc-format output is saved under `build/solve-<solver>/`, and a parsed roll-up to
 * `build/solve-<solver>.json`.
 *
 * No in-session comparison: to compare two solvers, run this once per solver (each writes its own
 * files) and diff offline (`parity-runs/compare.sh`). One solver's crash never taints another's.
 */
internal data class KlauseSearch(
    val engine: String = "portfolio", // cp | ls | portfolio (klause-cli -e)
    val processors: Int = Runtime.getRuntime().availableProcessors(),
    /** Follow the model's search annotation (the "fixed" track); false ⇒ free search (`-f`). */
    val fixed: Boolean = false,
)

@Serializable
internal data class SolveRow(
    val name: String,
    val kind: String, // "optimize" | "satisfy"
    val solver: String,
    /** true = feasible, false = infeasible (proved), null = unknown within budget. */
    val feasible: Boolean?,
    val objective: Double?,
    /** ms to the best incumbent (optimize) or to the first solution (satisfy); null when none. */
    val timeMs: Long?,
    /** optimum proved (optimize) or search closed UNSAT/exhausted. */
    val proven: Boolean,
    /** True when the objective is maximized (so a higher value is better) — for offline comparison. */
    val maximize: Boolean,
    val display: String,
    val stats: Map<String, String> = emptyMap(),
)

@Serializable
internal data class SolveResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val solver: String,
    val budgetMillis: Long,
    val command: String,
    val rows: List<SolveRow>,
)

internal object SolveMetric {
    private const val SOLVE_SEED = 3L

    /** Run [solverId] (`"klause"` or a registered MiniZinc reference id) over [entries]. [search]
     *  applies to klause (engine/processors/annotation); references take only processors + free.
     *
     *  When [profile] is set, the run switches to **profiling mode**: the klause engine is run
     *  IN-PROCESS under JFR (subprocess solves can't be sampled from the bench JVM), so the profile
     *  captures the actual `BacktrackSolver`/`LocalSearchSolver` hot paths. No JSON/cache is written
     *  in this mode — it measures the solver, not figures. */
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
        )
        val solver = label(solverId, settings)
        val outDir = File("build/solve-$solver").apply { mkdirs() }
        println()
        println("=== solve ($solver; ${budget.timeoutMillis}ms budget) ===")
        var command = ""
        val rows = entries.map { entry ->
            val optimize = entry.objective != null
            val kind = if (optimize) "optimize" else "satisfy"
            val r = runCatching {
                val key = BenchCache.keyFor(entry, solver, budget)
                BenchCache.load(key) ?: SolverInvocation.run(entry, solverId, settings, budget, optimize)
                    .also { BenchCache.store(key, it) }
            }.getOrElse {
                println("?? [${entry.name}] $kind $solver=ERROR: ${it.message ?: it::class.simpleName}")
                return@map errorRow(entry, kind, solver)
            }
            command = r.command
            File(outDir, entry.name.replace('/', '_') + ".out").writeText(r.rawOutput)
            val row = row(entry, kind, solver, r)
            val mark = if (row.feasible == null && !row.proven) "??" else "ok"
            println("$mark [${row.name}] $kind $solver=${row.display}")
            row
        }
        Reports.writeJson(
            "build/solve-$solver.json",
            SolveResults(
                Instant.now().toString(),
                Reports.readGitSha(),
                EnvInfo.capture(),
                solver,
                budget.timeoutMillis,
                command,
                rows,
            ),
        )
        val feas = rows.count { it.feasible == true }
        val prov = rows.count { it.proven }
        println("\n$feas/${rows.size} feasible, $prov proved  (raw output in $outDir/)")
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
        if (search.engine !in setOf("cp", "ls")) {
            println("profile= needs a single klause engine (engine=cp|ls); got '${search.engine}'")
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

    /** A single in-process klause solve for the profiler (cp → backtrack, ls → local search). */
    private fun solveInProcess(entry: ResolvedProblem, search: KlauseSearch, cancel: Cancellation) {
        when (search.engine) {
            "ls" -> LocalSearchSolver(entry.problem).solve(
                LocalSearchParams(randomSeed = SOLVE_SEED, cancellation = cancel, lsObjective = entry.lsObjective),
            )

            else -> {
                val params = (entry.searchParams?.takeIf { search.fixed })?.copy(cancellation = cancel)
                    ?: BacktrackPresets.conflictDriven(randomSeed = SOLVE_SEED, cancellation = cancel)
                val solver = BacktrackSolver(entry.problem)
                entry.objective?.let { solver.minimize(it, params) } ?: solver.solve(params)
            }
        }
    }

    private fun row(entry: ResolvedProblem, kind: String, solver: String, r: SolverInvocation.Result): SolveRow {
        val at = "@${r.timeToBestMs ?: "-"}ms"
        val display = when {
            kind != "optimize" -> when (r.feasible) {
                true -> "SAT$at"
                false -> "UNSAT"
                null -> "?"
            }

            r.objective == null -> "?"

            else -> "${if (r.proven) "opt" else "best"}=${r.objective}$at"
        }
        return SolveRow(
            entry.name, kind, solver,
            feasible = r.feasible,
            objective = r.objective,
            timeMs = r.timeToBestMs,
            proven = r.proven,
            maximize = entry.maximize,
            display = display,
            stats = r.stats,
        )
    }

    /** A stable, filesystem-safe label encoding the solver + settings (also the output dir/json name). */
    private fun label(solverId: String, s: SolverInvocation.Settings): String {
        if (solverId != SolverInvocation.KLAUSE) {
            return solverId + if (s.processors > 1) "-x${s.processors}" else ""
        }
        return "klause-${s.engine ?: "portfolio"}-x${s.processors}" + if (!s.free) "-ann" else ""
    }

    private fun errorRow(entry: ResolvedProblem, kind: String, solver: String): SolveRow = SolveRow(
        entry.name,
        kind,
        solver,
        feasible = null,
        objective = null,
        timeMs = null,
        proven = false,
        maximize = entry.maximize,
        display = "ERROR",
    )
}
