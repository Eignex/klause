package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.SequentialPortfolio
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Single-backend solve over a corpus. One [run] measures **one** solver — klause (an
 * [EngineMix] over `processors` workers, or the model's `fixed` annotation track) OR a single
 * in-process [Reference] (Choco / OR-Tools / Yuck) — and records, per instance, the objective
 * reached and its time-to-best (optimization) or feasibility (satisfaction), plus whether the
 * solver *proved* the result. Results are written to `build/solve-<solver>.json` and printed
 * row-by-row.
 *
 * There is deliberately no in-session reference comparison: to compare two solvers, run this
 * metric once per backend (each writes its own saved file) and diff the files offline. This
 * keeps each baseline clean — one solver's crash or warmup never contaminates another's number.
 */
internal data class KlauseSearch(
    val engine: EngineMix = EngineMix.MIXED,
    val processors: Int = Runtime.getRuntime().availableProcessors(),
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
    /** ms to the best incumbent (optimize) or to the solve verdict (satisfy); null when none. */
    val timeMs: Long?,
    /** optimum proved (optimize) or search closed UNSAT/exhausted (satisfy). */
    val proven: Boolean,
    val display: String,
)

@Serializable
internal data class SolveResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val solver: String,
    val budgetMillis: Long,
    val rows: List<SolveRow>,
)

internal object SolveMetric {
    /** Fixed RNG seed for every klause solve, so a run is reproducible. */
    private const val SOLVE_SEED = 3L

    /** Run [backend] (null = the klause [search]) over [entries] under [budget]. */
    fun run(
        entries: List<ResolvedProblem>,
        budget: Budget = Budget(),
        backend: Backend? = null,
        search: KlauseSearch = KlauseSearch(),
    ) {
        val ref = backend?.let { Reference.of(it) }
        val solver = ref?.name ?: klauseLabel(search)
        println()
        println("=== solve ($solver; ${budget.timeoutMillis}ms budget) ===")
        val rows = entries.map { entry ->
            val r = if (entry.objective == null) {
                satisfyRow(entry, budget, ref, search, solver)
            } else {
                optimizeRow(entry, requireNotNull(entry.objective), budget, ref, search, solver)
            }
            println(
                "${if (r.feasible == null && !r.proven) "?? " else "ok "}[${r.name}] ${r.kind} $solver=${r.display}",
            )
            r
        }
        Reports.writeJson(
            "build/solve-${solver.replace("[^A-Za-z0-9]".toRegex(), "-")}.json",
            SolveResults(
                Instant.now().toString(),
                Reports.readGitSha(),
                EnvInfo.capture(),
                solver,
                budget.timeoutMillis,
                rows,
            ),
        )
        val feas = rows.count { it.feasible == true }
        val prov = rows.count { it.proven }
        println("\n$feas/${rows.size} feasible, $prov proved")
    }

    private fun klauseLabel(s: KlauseSearch): String =
        if (s.fixed) "klause-fixed" else "klause-${s.engine.name.lowercase()}-x${s.processors}"

    // --- optimization ---------------------------------------------------------------------------

    private fun optimizeRow(
        entry: ResolvedProblem,
        obj: LinearObjective,
        budget: Budget,
        ref: Reference?,
        search: KlauseSearch,
        solver: String,
    ): SolveRow {
        warmup(entry, obj, ref)
        return if (ref == null) {
            val (mr, ms) = runCatching { klauseMinimizeTimed(entry, obj, budget, search) }
                .getOrElse { return errorRow(entry, "optimize", solver, it) }
            SolveRow(
                entry.name,
                "optimize",
                solver,
                feasible = feasibleOf(mr),
                objective = mr.objectiveValue,
                timeMs = ms,
                proven = mr is MinimizeResult.Optimal,
                display = optimizeStr(mr.objectiveValue, ms, mr is MinimizeResult.Optimal),
            )
        } else {
            val rt = runCatching {
                ref.minimizeTimed(
                    entry.problem,
                    obj,
                    budget,
                    referenceSearch(entry, search.fixed),
                    refProcessors(search),
                )
            }.getOrElse { return errorRow(entry, "optimize", solver, it) }
            SolveRow(
                entry.name,
                "optimize",
                solver,
                feasible = if (rt.value != null) true else null,
                objective = rt.value,
                timeMs = rt.timeToBestMs,
                proven = rt.proven,
                display = optimizeStr(rt.value, rt.timeToBestMs, rt.proven),
            )
        }
    }

    private fun optimizeStr(value: Double?, ms: Long?, proven: Boolean): String =
        if (value == null) "?" else "${if (proven) "opt" else "best"}=$value@${ms ?: "-"}ms"

    // --- satisfaction ---------------------------------------------------------------------------

    private fun satisfyRow(
        entry: ResolvedProblem,
        budget: Budget,
        ref: Reference?,
        search: KlauseSearch,
        solver: String,
    ): SolveRow {
        val t0 = System.currentTimeMillis()
        val result = runCatching {
            if (ref == null) {
                klauseSolve(entry, budget, search)
            } else {
                ref.solve(entry.problem, budget, referenceSearch(entry, search.fixed), refProcessors(search))
            }
        }.getOrElse { return errorRow(entry, "satisfy", solver, it) }
        val ms = System.currentTimeMillis() - t0
        val feasible = feasibleOf(result)
        return SolveRow(
            entry.name,
            "satisfy",
            solver,
            feasible = feasible,
            objective = null,
            timeMs = if (feasible == true) ms else null,
            proven = result is SolveResult.Unsat,
            display = when (feasible) {
                true -> "SAT@${ms}ms"
                false -> "UNSAT"
                null -> "?"
            },
        )
    }

    // --- klause search --------------------------------------------------------------------------

    private fun scenarioFor(s: KlauseSearch, kind: Kind): PortfolioScenario = if (s.processors == 1) {
        PortfolioScenario.sequential(kind, s.engine, seed = SOLVE_SEED)
    } else {
        PortfolioScenario.parallel(s.processors, kind, s.engine, seed = SOLVE_SEED)
    }

    private fun executorFor(
        entry: ResolvedProblem,
        scenario: PortfolioScenario,
        objective: LinearObjective?,
        onEvent: ((worker: String, event: SearchEvent) -> Unit)? = null,
    ): PortfolioExecutor {
        val workers = PortfolioBuilder.build(
            entry.problem,
            scenario,
            objective = objective,
            lsObjective = entry.lsObjective,
            definitionalSweep = entry.definitionalSweep,
            onEvent = onEvent,
        )
        return if (scenario.threads == 1) SequentialPortfolio.exp3(workers, scenario.seed) else Portfolio(workers)
    }

    /** Fixed-track params: the model's compiled annotation if present, else a conflict-driven free
     *  search; both carry the run deadline. */
    private fun fixedParams(entry: ResolvedProblem, deadline: Long): BacktrackParams {
        val cancel = Cancellation { System.currentTimeMillis() > deadline }
        return entry.searchParams?.copy(cancellation = cancel)
            ?: BacktrackPresets.conflictDriven(randomSeed = SOLVE_SEED, cancellation = cancel)
    }

    private fun klauseSolve(entry: ResolvedProblem, budget: Budget, search: KlauseSearch): SolveResult {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        if (search.fixed) return BacktrackSolver(entry.problem).solve(fixedParams(entry, deadline))
        return executorFor(entry, scenarioFor(search, Kind.CSP), objective = null).use {
            it.solve(Cancellation { System.currentTimeMillis() > deadline })
        }
    }

    /** Minimise with klause's search, capturing time-to-best off the [SearchEvent.Incumbent] seam. */
    private fun klauseMinimizeTimed(
        entry: ResolvedProblem,
        obj: LinearObjective,
        budget: Budget,
        search: KlauseSearch,
    ): Pair<MinimizeResult, Long?> {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        val times = EngineTimes()
        if (search.fixed) {
            val params = fixedParams(entry, deadline).copy(onEvent = times.listener)
            return BacktrackSolver(entry.problem).minimize(obj, params) to times.bestMs.takeIf { it >= 0 }
        }
        val exec = executorFor(entry, scenarioFor(search, Kind.COP), objective = obj) { _, e -> times.listener(e) }
        val mr = exec.use { it.minimize(Cancellation { System.currentTimeMillis() > deadline }) }
        return mr to times.bestMs.takeIf { it >= 0 }
    }

    // --- reference plumbing ---------------------------------------------------------------------

    /** Choco mirrors the model annotation on the fixed track; other references ignore it. */
    private fun referenceSearch(entry: ResolvedProblem, fixed: Boolean) = if (fixed) entry.searchParams else null

    /** The fixed track is single-thread (follow the annotation); otherwise the reference matches
     *  klause's worker count. */
    private fun refProcessors(s: KlauseSearch) = if (s.fixed) 1 else s.processors

    // --- shared -------------------------------------------------------------------------------

    /** JIT-warm the chosen backend on a short slice so a cold first solve's compilation doesn't
     *  dominate time-to-best on fast rows. The klause warmup builds fresh heuristics (it must not
     *  share solution-guided state with the timed solve); the reference builds a fresh model. */
    private fun warmup(entry: ResolvedProblem, obj: LinearObjective, ref: Reference?) {
        val warmMs = System.getProperty("klause.bench.solve.warmupMs")?.toLongOrNull() ?: 2000L
        if (ref == null) {
            runCatching {
                val dl = System.currentTimeMillis() + warmMs
                BacktrackSolver(entry.problem).minimize(
                    obj,
                    BacktrackPresets.conflictDriven(
                        randomSeed = SOLVE_SEED,
                        cancellation = Cancellation { System.currentTimeMillis() > dl },
                    ),
                )
            }
        } else if (ref.name == "choco") {
            runCatching { ChocoSolver(entry.problem).minimizeTimed(obj, ChocoParams(warmMs)) }
        }
    }

    private fun feasibleOf(r: MinimizeResult): Boolean? = when (r) {
        is MinimizeResult.Optimal, is MinimizeResult.BestFound -> true
        is MinimizeResult.Infeasible -> false
        is MinimizeResult.Unknown -> null
    }

    private fun feasibleOf(r: SolveResult): Boolean? = when (r) {
        is SolveResult.Sat -> true
        is SolveResult.Unsat -> false
        is SolveResult.Unknown -> null
    }

    private fun errorRow(entry: ResolvedProblem, kind: String, solver: String, e: Throwable): SolveRow = SolveRow(
        entry.name,
        kind,
        solver,
        feasible = null,
        objective = null,
        timeMs = null,
        proven = false,
        display = "ERROR: ${e.message ?: e::class.simpleName}",
    )
}
