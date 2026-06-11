package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.portfolio.CompetitionMode
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioExecutor
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.SequentialPortfolio
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Differential parity: solve each problem with **klause** (complete backtracking) and an
 * in-process [Reference] solver (Choco or OR-Tools) on the same
 * [com.eignex.klause.solver.Problem], then check both against each other and against the
 * recorded [Expected] oracle. The comparison is in-process — no external solver binary.
 *
 *  - satisfaction problems compare feasibility (SAT/UNSAT) three ways (klause, reference, expected);
 *  - optimization problems additionally compare the optimal objective value.
 *
 * The reference defaults to the target's choice and can be overridden with
 * `-Dklause.bench.parity.reference=choco|ortools`.
 */
@Serializable
internal data class ParityRow(
    val name: String,
    val kind: String, // "satisfy" | "optimize"
    val verdict: String, // OK | MISMATCH | KLAUSE_ERROR | REFERENCE_ERROR
    val klause: String,
    val reference: String,
    val referenceSolver: String, // "choco" | "ortools"
    val expected: String,
    val detail: String = "",
)

@Serializable
internal data class ParityResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val rows: List<ParityRow>,
) {
    val mismatches: Int get() = rows.count { it.verdict != "OK" }
}

internal object ParityMetric {
    /** Fixed RNG seed for every klause solve in a parity run, so a sweep is reproducible. */
    private const val PARITY_SEED = 3L

    fun run(
        entries: List<ResolvedProblem>,
        budget: Budget = Budget(),
        reference: Backend = Backend.CHOCO,
        mode: CompetitionMode = CompetitionMode.OPEN,
        threads: Int = Runtime.getRuntime().availableProcessors(),
    ) {
        val ref = System.getProperty(
            "klause.bench.parity.reference",
        )?.let { Reference.byId(it) } ?: Reference.of(reference)
        // Sharding for parallel sweeps is applied at selection time (klause.bench.shard in
        // BenchCli.select, before resolution) so disjoint workers never race on the shared
        // mzn-fzn cache; the prop is read here only to suffix the report path.
        val shard = System.getProperty("klause.bench.shard")?.let(::parseShard)
        // Reference-column cache: -Dklause.bench.parity.referenceCache=<previous sweep log>
        // reuses the reference solver's printed results row-by-row instead of re-running it,
        // halving sweep wall time. Rows absent from the cache run the reference live.
        val cache = System.getProperty("klause.bench.parity.referenceCache")?.let(::parseReferenceCache).orEmpty()
        val effectiveThreads = if (mode == CompetitionMode.FREE || mode == CompetitionMode.FIXED) 1 else threads
        println()
        println(
            "=== parity (klause $mode" + (if (effectiveThreads > 1) " ×$effectiveThreads" else "") +
                " vs ${ref.name} reference; checked against recorded expected) ===",
        )
        val rows = entries.map { entry ->
            val r = row(entry, budget, ref, cache[entry.name], mode, effectiveThreads)
            val mark = if (r.verdict == "OK") "ok " else "!! "
            println(
                "$mark[${r.name}] ${r.kind} klause=${r.klause} " +
                    "${r.referenceSolver}=${r.reference} expected=${r.expected}" +
                    if (r.detail.isNotEmpty()) " — ${r.detail}" else "",
            )
            r
        }
        val results = ParityResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), rows)
        val reportPath = if (shard == null) {
            "build/parity-report.json"
        } else {
            "build/parity-report-shard-${shard.first}-of-${shard.second}.json"
        }
        Reports.writeJson(reportPath, results)
        if (mode == CompetitionMode.FIXED) {
            val followed = entries.count { it.searchParams != null }
            println(
                "--- fixed track: $followed/${entries.size} followed the model annotation, " +
                    "${entries.size - followed} fell back to free search (no annotation) ---",
            )
        }
        println("\n${rows.count { it.verdict == "OK" }}/${rows.size} OK, ${results.mismatches} mismatch(es)")
        if (System.getProperty("klause.bench.parity.failOnMismatch")?.toBoolean() == true && results.mismatches > 0) {
            error("${results.mismatches} parity mismatch(es)")
        }
    }

    private fun parseShard(spec: String): Pair<Int, Int> {
        val (i, n) = spec.split("/").map { it.trim().toInt() }
        require(n > 0 && i in 0 until n) { "shard must be i/n with 0 <= i < n, got $spec" }
        return i to n
    }

    /** Cached reference outcome parsed back from a previous sweep log line. */
    private data class CachedRef(val feasible: Boolean?, val objective: Double?, val display: String)

    /** Parse `ok|!! [name] kind klause=… <ref>=<value> expected=…` sweep lines into
     *  name → cached reference outcome. Unparseable lines are skipped. */
    private fun parseReferenceCache(path: String): Map<String, CachedRef> {
        val re = Regex("""^(?:ok|!!)\s+\[(.+?)]\s+\S+\s+klause=\S+\s+\S+?=(\S+)\s+expected=""")
        val out = HashMap<String, CachedRef>()
        java.io.File(path).useLines { lines ->
            for (line in lines) {
                val m = re.find(line) ?: continue
                val (name, value) = m.destructured
                out[name] = CachedRef(
                    feasible = when {
                        value == "SAT" -> true
                        value == "UNSAT" -> false
                        value.startsWith("opt=") || value.startsWith("best=") -> true
                        else -> null
                    },
                    objective = value.substringAfter("=", "").toDoubleOrNull(),
                    display = value,
                )
            }
        }
        return out
    }

    private fun row(
        entry: ResolvedProblem,
        budget: Budget,
        ref: Reference,
        cached: CachedRef?,
        mode: CompetitionMode,
        threads: Int,
    ): ParityRow {
        val obj = entry.objective
        return if (obj == null) {
            satisfyRow(entry, budget, ref, cached, mode, threads)
        } else {
            optimizeRow(entry, obj, budget, ref, cached, mode, threads)
        }
    }

    /** The reference mirrors the model's search annotation only in the fixed track (a true two-sided
     *  fixed comparison, sound now that `applyFixedSearch` works around the LCG bug); every other mode
     *  lets the reference use its own default search. */
    private fun referenceSearch(entry: ResolvedProblem, mode: CompetitionMode) =
        if (mode == CompetitionMode.FIXED) entry.searchParams else null

    private fun satisfyRow(
        entry: ResolvedProblem,
        budget: Budget,
        ref: Reference,
        cached: CachedRef?,
        mode: CompetitionMode,
        threads: Int,
    ): ParityRow {
        val klause = runCatching { feasibility(klauseSolve(entry, budget, mode, threads)) }
            .getOrElse { return errorRow(entry, "satisfy", "KLAUSE_ERROR", ref, it) }
        val refFeas = if (cached != null) {
            cached.feasible
        } else {
            runCatching { feasibility(ref.solve(entry.problem, budget, referenceSearch(entry, mode))) }
                .getOrElse { return errorRow(entry, "satisfy", "REFERENCE_ERROR", ref, it) }
        }
        val exp = expectedFeasible(entry.ref.expected)
        val verdict = when {
            !agree(klause, refFeas) -> "MISMATCH"
            exp != null && refFeas != null && refFeas != exp -> "MISMATCH"
            else -> "OK"
        }
        return ParityRow(entry.name, "satisfy", verdict, feasStr(klause), feasStr(refFeas), ref.name, feasStr(exp))
    }

    private fun optimizeRow(
        entry: ResolvedProblem,
        obj: LinearObjective,
        budget: Budget,
        ref: Reference,
        cached: CachedRef?,
        mode: CompetitionMode,
        threads: Int,
    ): ParityRow {
        if (timedMode) return timedOptimizeRow(entry, obj, budget)
        val klause = runCatching { klauseMinimize(entry, obj, budget, mode, threads) }
            .getOrElse { return errorRow(entry, "optimize", "KLAUSE_ERROR", ref, it) }
        val kv = klause.objectiveValue
        val cv: Double?
        val refDisplay: String
        if (cached != null) {
            cv = cached.objective
            refDisplay = cached.display
        } else {
            val refRes = runCatching {
                ref.minimize(entry.problem, obj, budget, referenceSearch(entry, mode))
            }.getOrElse { return errorRow(entry, "optimize", "REFERENCE_ERROR", ref, it) }
            cv = refRes.objectiveValue
            refDisplay = optStr(refRes)
        }
        val exp = (entry.ref.expected as? Expected.Opt)?.value?.toDouble()
        val verdict = when {
            kv == null || cv == null -> if (kv == cv) "OK" else "MISMATCH"
            kv != cv -> "MISMATCH"
            exp != null && cv != exp -> "MISMATCH"
            else -> "OK"
        }
        return ParityRow(
            entry.name,
            "optimize",
            verdict,
            optStr(klause),
            refDisplay,
            ref.name,
            exp?.toString() ?: "?",
        )
    }

    // -Dklause.bench.parity.timed scores the COP goal as a free-search race: a single
    // klause worker on its own free conflict-driven search vs Choco-CP-SAT (LCG) on its
    // default search, where "beat" means a strictly better objective OR the same objective
    // reached sooner. Both run single-threaded (no portfolio, no parallelism). A fixed-track
    // variant (both following the model annotation) is not viable: choco-LCG is unsound under
    // a mirrored fixed search (see rasros/choco-lcg-false-unsat), so each solver uses its own
    // search — the comparison every solver actually runs in competition.
    private val timedMode = System.getProperty("klause.bench.parity.timed")?.toBoolean() ?: false

    private fun timedOptimizeRow(entry: ResolvedProblem, obj: LinearObjective, budget: Budget): ParityRow {
        // Warm the JVM before timing. The competition runs a native binary with no JIT
        // warmup cost; on the JVM a cold first solve spends seconds in compilation, which
        // dominates time-to-best on fast rows and would make every quick COP look slow.
        // A short throwaway solve per engine moves the hot paths to compiled code so the
        // timed solve reflects steady-state speed. Budget-capped small so warmup is cheap.
        warmup(entry, obj)

        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        // klause: single free conflict-driven worker, time-stamped at each incumbent. (Not
        // the model annotation — forcing it cripples klause on rows whose prescribed search
        // suits a different engine, e.g. stochastic-fjsp where free search proves the optimum
        // in tens of ms while the annotation stalls. Choco likewise runs its own default.)
        var kBestMillis: Long? = null
        val t0 = System.currentTimeMillis()
        val kParams = BacktrackPresets.conflictDriven(
            randomSeed = 3L,
            cancellation = Cancellation { System.currentTimeMillis() > deadline },
            onEvent = { e -> if (e is SearchEvent.Incumbent) kBestMillis = System.currentTimeMillis() - t0 },
        )
        val k = runCatching { BacktrackSolver(entry.problem).minimize(obj, kParams) }
            .getOrElse { return errorRow(entry, "optimize", "KLAUSE_ERROR", Reference.of(Backend.CHOCO), it) }
        val kv = k.objectiveValue
        // Choco-CP-SAT (LCG) reference under the same prescribed search.
        val c = runCatching {
            ChocoSolver(entry.problem).minimizeTimed(
                obj as LinearObjective,
                ChocoParams(budget.timeoutMillis, lcg = true), // default search; see note above
            )
        }.getOrElse { return errorRow(entry, "optimize", "REFERENCE_ERROR", Reference.of(Backend.CHOCO), it) }
        val cv = c.value
        val beat = when {
            kv == null -> false

            cv == null -> true

            // klause found something, reference did not
            kv < cv -> true

            kv == cv -> (kBestMillis ?: Long.MAX_VALUE) < (c.timeToBestMillis ?: Long.MAX_VALUE)

            else -> false
        }
        val detail = "k=${fmt(kv)}@${kBestMillis ?: "-"}ms cpsat=${fmt(cv)}@${c.timeToBestMillis ?: "-"}ms"
        return ParityRow(
            entry.name,
            "optimize",
            if (beat) "OK" else "MISMATCH",
            fmt(kv),
            fmt(cv),
            "choco-cpsat",
            "?",
            detail,
        )
    }

    private fun fmt(v: Double?): String = v?.toString() ?: "?"

    /** Throwaway short solve per engine to JIT-warm the hot paths before timing (see
     *  [timedOptimizeRow]). The warmup budget is fixed and small; cancellation cuts it off
     *  so warming a hard row costs at most this slice. Results are discarded.
     *
     *  The klause warmup must NOT share search-heuristic instances with the timed solve: a
     *  solution-guided value heuristic remembers the best assignment it has seen, so reusing
     *  one would let the timed solve's first dive reconstruct the warmup's incumbent and
     *  measure time-to-best from an unfairly warm start. [BacktrackPresets.conflictDriven]
     *  constructs its own fresh heuristics each call — the same composition the timed solve
     *  runs, so it JIT-warms the exact hot paths (propagation, BCP, conflict analysis,
     *  branch-and-bound) while the timed solve still starts from pristine heuristic state. The
     *  reference builds a fresh model per call and starts cold for the same reason. */
    private fun warmup(entry: ResolvedProblem, obj: LinearObjective) {
        val warmMs = System.getProperty("klause.bench.parity.warmupMs")?.toLongOrNull() ?: 2000L
        runCatching {
            val dl = System.currentTimeMillis() + warmMs
            BacktrackSolver(entry.problem).minimize(
                obj,
                BacktrackPresets.conflictDriven(
                    randomSeed = 3L,
                    cancellation = Cancellation { System.currentTimeMillis() > dl },
                ),
            )
        }
        runCatching {
            ChocoSolver(entry.problem).minimizeTimed(
                obj,
                ChocoParams(warmMs, lcg = true),
            )
        }
    }

    /**
     * Build the klause executor for a [mode] over [entry], sharing one objective bound. Every track
     * but FIXED maps to a [PortfolioScenario] (see [PortfolioScenario.forMode]) materialised through
     * the one [PortfolioBuilder] path — a single-core [SequentialPortfolio] when the scenario is
     * 1-thread (FREE), a parallel [Portfolio] otherwise (PARALLEL/OPEN/LOCAL_SEARCH). FIXED is the
     * annotation track and has no portfolio; it is handled directly in [klauseSolve]/[klauseMinimize].
     */
    private fun executorFor(
        entry: ResolvedProblem,
        scenario: PortfolioScenario,
        objective: LinearObjective?,
    ): PortfolioExecutor {
        val workers = PortfolioBuilder.build(
            entry.problem,
            scenario,
            objective = objective,
            lsObjective = entry.lsObjective,
            definitionalSweep = entry.definitionalSweep,
        )
        return if (scenario.threads == 1) SequentialPortfolio.exp3(workers, scenario.seed) else Portfolio(workers)
    }

    /** The scenario for a non-FIXED [mode]; [PortfolioScenario.forMode] only returns null for FIXED,
     *  which the callers handle before reaching here. */
    private fun scenarioFor(mode: CompetitionMode, kind: Kind, threads: Int): PortfolioScenario =
        PortfolioScenario.forMode(mode, kind, threads, seed = PARITY_SEED)
            ?: error("forMode($mode) has no scenario — FIXED must be handled by the annotation path")

    /** The FIXED-track params: the model's compiled annotation if present (a true fixed search),
     *  else a single conflict-driven free search as the fallback. Both carry the run deadline. */
    private fun fixedParams(entry: ResolvedProblem, deadline: Long): BacktrackParams {
        val cancel = Cancellation { System.currentTimeMillis() > deadline }
        return entry.searchParams?.copy(cancellation = cancel)
            ?: BacktrackPresets.conflictDriven(randomSeed = PARITY_SEED, cancellation = cancel)
    }

    private fun klauseSolve(entry: ResolvedProblem, budget: Budget, mode: CompetitionMode, threads: Int): SolveResult {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        if (mode == CompetitionMode.FIXED) return BacktrackSolver(entry.problem).solve(fixedParams(entry, deadline))
        val scenario = scenarioFor(mode, Kind.CSP, threads)
        return executorFor(entry, scenario, objective = null).use {
            it.solve(Cancellation { System.currentTimeMillis() > deadline })
        }
    }

    private fun klauseMinimize(
        entry: ResolvedProblem,
        obj: LinearObjective,
        budget: Budget,
        mode: CompetitionMode,
        threads: Int,
    ): MinimizeResult {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        if (mode == CompetitionMode.FIXED) {
            return BacktrackSolver(entry.problem).minimize(obj, fixedParams(entry, deadline))
        }
        val scenario = scenarioFor(mode, Kind.COP, threads)
        return executorFor(entry, scenario, objective = obj).use {
            it.minimize(Cancellation { System.currentTimeMillis() > deadline })
        }
    }

    /** true = feasible, false = infeasible, null = unknown/timeout. */
    private fun feasibility(r: SolveResult): Boolean? = when (r) {
        is SolveResult.Sat -> true
        is SolveResult.Unsat -> false
        is SolveResult.Unknown -> null
    }

    private fun expectedFeasible(e: Expected): Boolean? = when (e) {
        Expected.Sat -> true
        Expected.Unsat -> false
        is Expected.Opt -> true
        Expected.Unknown -> null
    }

    /** Two feasibility verdicts agree if neither is a definite contradiction of the other. */
    private fun agree(a: Boolean?, b: Boolean?): Boolean = a == null || b == null || a == b

    private fun feasStr(b: Boolean?): String = when (b) {
        true -> "SAT"
        false -> "UNSAT"
        null -> "?"
    }

    private fun optStr(r: MinimizeResult): String = when (r) {
        is MinimizeResult.Optimal -> "opt=${r.objective}"
        is MinimizeResult.BestFound -> "best=${r.objective}"
        is MinimizeResult.Infeasible -> "UNSAT"
        is MinimizeResult.Unknown -> "?"
    }

    private fun errorRow(
        entry: ResolvedProblem,
        kind: String,
        verdict: String,
        ref: Reference,
        t: Throwable,
    ): ParityRow {
        if (System.getProperty("klause.bench.parity.trace")?.toBoolean() == true) {
            System.err.println(">>> $verdict on ${entry.name}:")
            t.printStackTrace()
        }
        return ParityRow(
            entry.name,
            kind,
            verdict,
            "-",
            "-",
            ref.name,
            entry.ref.expected.toString(),
            t.message?.take(160) ?: t::class.simpleName ?: "error",
        )
    }
}
