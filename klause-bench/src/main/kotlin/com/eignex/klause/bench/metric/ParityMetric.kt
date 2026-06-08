package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.LastConflict
import com.eignex.klause.solver.backtrack.SolutionGuided
import com.eignex.klause.solver.backtrack.Vsids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlinx.serialization.Serializable

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
data class ParityRow(
    val name: String,
    val kind: String,            // "satisfy" | "optimize"
    val verdict: String,         // OK | MISMATCH | KLAUSE_ERROR | REFERENCE_ERROR
    val klause: String,
    val reference: String,
    val referenceSolver: String, // "choco" | "ortools"
    val expected: String,
    val detail: String = "",
)

@Serializable
data class ParityResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val rows: List<ParityRow>,
) {
    val mismatches: Int get() = rows.count { it.verdict != "OK" }
}

object ParityMetric {
    fun run(entries: List<ResolvedProblem>, budget: Budget = Budget(), reference: Backend = Backend.CHOCO) {
        val ref = System.getProperty("klause.bench.parity.reference")?.let { Reference.byId(it) } ?: Reference.of(reference)
        // Sharding for parallel sweeps is applied at selection time (klause.bench.shard in
        // BenchCli.select, before resolution) so disjoint workers never race on the shared
        // mzn-fzn cache; the prop is read here only to suffix the report path.
        val shard = System.getProperty("klause.bench.shard")?.let(::parseShard)
        // Reference-column cache: -Dklause.bench.parity.referenceCache=<previous sweep log>
        // reuses the reference solver's printed results row-by-row instead of re-running it,
        // halving sweep wall time. Rows absent from the cache run the reference live.
        val cache = System.getProperty("klause.bench.parity.referenceCache")?.let(::parseReferenceCache).orEmpty()
        println()
        println("=== parity (klause backtrack vs ${ref.name} reference; checked against recorded expected) ===")
        val rows = entries.map { entry ->
            val r = row(entry, budget, ref, cache[entry.name])
            val mark = if (r.verdict == "OK") "ok " else "!! "
            println("$mark[${r.name}] ${r.kind} klause=${r.klause} ${r.referenceSolver}=${r.reference} expected=${r.expected}" +
                if (r.detail.isNotEmpty()) " — ${r.detail}" else "")
            r
        }
        val results = ParityResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), rows)
        val reportPath = if (shard == null) {
            "build/parity-report.json"
        } else {
            "build/parity-report-shard-${shard.first}-of-${shard.second}.json"
        }
        Reports.writeJson(reportPath, results)
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

    private fun row(entry: ResolvedProblem, budget: Budget, ref: Reference, cached: CachedRef?): ParityRow {
        val obj = entry.objective
        return if (obj == null) satisfyRow(entry, budget, ref, cached) else optimizeRow(entry, obj, budget, ref, cached)
    }

    private fun satisfyRow(entry: ResolvedProblem, budget: Budget, ref: Reference, cached: CachedRef?): ParityRow {
        val klause = runCatching { feasibility(klauseSolve(entry, budget)) }
            .getOrElse { return errorRow(entry, "satisfy", "KLAUSE_ERROR", ref, it) }
        val refFeas = if (cached != null) {
            cached.feasible
        } else {
            runCatching { feasibility(ref.solve(entry.problem, budget, if (fixedMode) entry.searchParams else null)) }
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
        obj: Objective,
        budget: Budget,
        ref: Reference,
        cached: CachedRef?,
    ): ParityRow {
        if (timedMode) return timedOptimizeRow(entry, obj, budget)
        val klause = runCatching { klauseMinimize(entry, obj, budget) }
            .getOrElse { return errorRow(entry, "optimize", "KLAUSE_ERROR", ref, it) }
        val kv = klause.objectiveValue
        val cv: Double?
        val refDisplay: String
        if (cached != null) {
            cv = cached.objective
            refDisplay = cached.display
        } else {
            val refRes = runCatching {
                ref.minimize(entry.problem, obj, budget, if (fixedMode) entry.searchParams else null)
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
        return ParityRow(entry.name, "optimize", verdict,
            optStr(klause), refDisplay, ref.name, exp?.toString() ?: "?")
    }

    // -Dklause.bench.parity.timed scores the real fixed-track COP goal: a single
    // annotation-following klause worker vs Choco-CP-SAT (LCG) under the same prescribed
    // search, where "beat" means a strictly better objective OR the same objective reached
    // sooner. Both solvers run single-threaded; the row records each side's final value and
    // the wall-clock at its last incumbent, and is marked ok iff klause beats.
    private val timedMode = System.getProperty("klause.bench.parity.timed")?.toBoolean() ?: false

    private fun timedOptimizeRow(entry: ResolvedProblem, obj: Objective, budget: Budget): ParityRow {
        // Warm the JVM before timing. The competition runs a native binary with no JIT
        // warmup cost; on the JVM a cold first solve spends seconds in compilation, which
        // dominates time-to-best on fast rows and would make every quick COP look slow.
        // A short throwaway solve per engine moves the hot paths to compiled code so the
        // timed solve reflects steady-state speed. Budget-capped small so warmup is cheap.
        warmup(entry, obj)

        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        // klause: fixed-track single worker, time-stamped at each incumbent.
        var kBestMillis: Long? = null
        val t0 = System.currentTimeMillis()
        val kParams = fixedParams(entry, deadline).copy(
            onEvent = { e -> if (e is SearchEvent.Incumbent) kBestMillis = System.currentTimeMillis() - t0 },
        )
        val k = runCatching { BacktrackSolver(entry.problem).minimize(obj, kParams) }
            .getOrElse { return errorRow(entry, "optimize", "KLAUSE_ERROR", Reference.of(Backend.CHOCO), it) }
        val kv = k.objectiveValue
        // Choco-CP-SAT (LCG) reference under the same prescribed search.
        val c = runCatching {
            ChocoSolver(entry.problem).minimizeTimed(
                obj as LinearObjective,
                ChocoParams(budget.timeoutMillis, lcg = true, fixedSearch = entry.searchParams),
            )
        }.getOrElse { return errorRow(entry, "optimize", "REFERENCE_ERROR", Reference.of(Backend.CHOCO), it) }
        val cv = c.value
        val beat = when {
            kv == null -> false
            cv == null -> true // klause found something, reference did not
            kv < cv -> true
            kv == cv -> (kBestMillis ?: Long.MAX_VALUE) < (c.timeToBestMillis ?: Long.MAX_VALUE)
            else -> false
        }
        val detail = "k=${fmt(kv)}@${kBestMillis ?: "-"}ms cpsat=${fmt(cv)}@${c.timeToBestMillis ?: "-"}ms"
        return ParityRow(
            entry.name, "optimize", if (beat) "OK" else "MISMATCH",
            fmt(kv), fmt(cv), "choco-cpsat", "?", detail,
        )
    }

    private fun fmt(v: Double?): String = v?.toString() ?: "?"

    /** Throwaway short solve per engine to JIT-warm the hot paths before timing (see
     *  [timedOptimizeRow]). The warmup budget is fixed and small; cancellation cuts it off
     *  so warming a hard row costs at most this slice. Results are discarded.
     *
     *  The klause warmup must NOT share search-heuristic instances with the timed solve.
     *  [fixedParams] returns `entry.searchParams.copy(...)`, a shallow copy that aliases the
     *  same stateful heuristic objects, and the default fixed-track value heuristic
     *  ([SolutionGuided]) remembers the best assignment it has seen. If the warmup solved
     *  through those instances, the timed solve's first dive would reconstruct the warmup's
     *  incumbent immediately, so the warmup would be solution-warming, not just JIT-warming,
     *  and time-to-best would be measured from an unfairly warm start. The reference builds a
     *  fresh model per call and starts cold, so we warm with [conflictDrivenParams], which
     *  constructs its own fresh heuristics each call: it JIT-warms the shared engine hot paths
     *  (propagation, BCP, conflict analysis, branch-and-bound) while leaving the annotated
     *  [entry.searchParams] heuristics pristine for the timed solve. */
    private fun warmup(entry: ResolvedProblem, obj: Objective) {
        val warmMs = System.getProperty("klause.bench.parity.warmupMs")?.toLongOrNull() ?: 2000L
        runCatching {
            val dl = System.currentTimeMillis() + warmMs
            BacktrackSolver(entry.problem).minimize(
                obj,
                conflictDrivenParams().copy(cancellation = Cancellation { System.currentTimeMillis() > dl }),
            )
        }
        runCatching {
            ChocoSolver(entry.problem).minimizeTimed(
                obj as LinearObjective,
                ChocoParams(warmMs, lcg = true, fixedSearch = entry.searchParams),
            )
        }
    }

    // Luby restarts everywhere: the anytime configuration. Branch-and-bound leaves a
    // permanent blocking nogood per incumbent, so restarts diversify without revisiting
    // solved leaves; on plateau-prone instances they are the difference between stalling
    // on the first incumbents and walking to the optimum.
    private fun freeParams(): BacktrackParams = BacktrackParams(randomSeed = 1L, lubyRestartBase = 256L)

    /** Conflict-driven free search: last-conflict probing over VSIDS activity, solution-
     *  guided value order, phase saving. The A/B over the scheduling tail picked this
     *  composition decisively — it proves rcpsp-wet and shortest_path in seconds and takes
     *  celar from 9344 to 2323 where the random free worker and the annotation both stall. */
    private fun conflictDrivenParams(): BacktrackParams = BacktrackParams(
        randomSeed = 3L,
        variableHeuristic = LastConflict(Vsids()),
        valueHeuristic = SolutionGuided(IndomainMin),
        phaseSaving = true,
        lubyRestartBase = 256L,
    )

    /**
     * The klause side of a row: a portfolio racing the model's annotated search (when
     * present), the engine's free default, and a conflict-driven VSIDS composition, all
     * sharing the objective bound. The single-config sweeps split the corpus three ways —
     * annotations win the structured reach rows, random free search wins plateau rows,
     * and the conflict-driven worker wins the scheduling tail — so the bench measures the
     * race, the same shape the competition entry runs.
     */
    private fun workers(entry: ResolvedProblem, objective: Objective?): List<PortfolioWorker> {
        val free = PortfolioWorker.of(
            "free", BacktrackSolver(entry.problem).session(), freeParams(), objective,
        ) { p, supplier -> p.copy(objectiveBoundSupplier = supplier) }
        val conflictDriven = PortfolioWorker.of(
            "conflict-driven", BacktrackSolver(entry.problem).session(), conflictDrivenParams(), objective,
        ) { p, supplier -> p.copy(objectiveBoundSupplier = supplier) }
        val annotated = entry.searchParams?.let { ann ->
            PortfolioWorker.of(
                "annotated", BacktrackSolver(entry.problem).session(),
                ann.copy(randomSeed = 2L, lubyRestartBase = 256L), objective,
            ) { p, supplier -> p.copy(objectiveBoundSupplier = supplier) }
        }
        // Seed twins for the two strongest free compositions: when a close-call row's
        // run-to-run variance exceeds its gap to the reference, a second seed with the
        // shared bound flips it — the five-worker sweep flipped five rows over the
        // three-worker config with zero regressions.
        val free2 = PortfolioWorker.of(
            "free#2", BacktrackSolver(entry.problem).session(), freeParams().copy(randomSeed = 11L), objective,
        ) { p, supplier -> p.copy(objectiveBoundSupplier = supplier) }
        val conflictDriven2 = PortfolioWorker.of(
            "conflict-driven#2", BacktrackSolver(entry.problem).session(),
            conflictDrivenParams().copy(randomSeed = 13L), objective,
        ) { p, supplier -> p.copy(objectiveBoundSupplier = supplier) }
        val xor = entry.xorSearchParams?.let { xs ->
            PortfolioWorker.of(
                "xor", BacktrackSolver(entry.problem).session(),
                xs.copy(randomSeed = 4L, lubyRestartBase = 256L), objective,
            ) { p, supplier -> p.copy(objectiveBoundSupplier = supplier) }
        }
        return listOfNotNull(free, free2, conflictDriven, conflictDriven2, annotated, xor)
    }

    // -Dklause.bench.parity.mode=fixed scores the fixed competition track: a single
    // klause worker that follows the model's search annotation (the engine's
    // conflict-driven free composition where the model has none), against a reference
    // that mirrors the same prescribed search. Default "portfolio" races the multi-worker
    // parallel-track configuration.
    private val fixedMode = System.getProperty("klause.bench.parity.mode") == "fixed"

    private fun fixedParams(entry: ResolvedProblem, deadline: Long): BacktrackParams =
        (entry.searchParams ?: conflictDrivenParams()).copy(
            randomSeed = 1L,
            lubyRestartBase = 256L,
            cancellation = Cancellation { System.currentTimeMillis() > deadline },
        )

    private fun klauseSolve(entry: ResolvedProblem, budget: Budget): SolveResult {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        if (fixedMode) return BacktrackSolver(entry.problem).solve(fixedParams(entry, deadline))
        return runBlocking(Dispatchers.Default) {
            Portfolio(workers(entry, objective = null))
                .solve(Cancellation { System.currentTimeMillis() > deadline })
        }
    }

    private fun klauseMinimize(entry: ResolvedProblem, obj: Objective, budget: Budget): MinimizeResult {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        if (fixedMode) return BacktrackSolver(entry.problem).minimize(obj, fixedParams(entry, deadline))
        return runBlocking(Dispatchers.Default) {
            Portfolio(workers(entry, obj))
                .minimize(Cancellation { System.currentTimeMillis() > deadline })
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

    private fun feasStr(b: Boolean?): String = when (b) { true -> "SAT"; false -> "UNSAT"; null -> "?" }

    private fun optStr(r: MinimizeResult): String = when (r) {
        is MinimizeResult.Optimal -> "opt=${r.objective}"
        is MinimizeResult.BestFound -> "best=${r.objective}"
        is MinimizeResult.Infeasible -> "UNSAT"
        is MinimizeResult.Unknown -> "?"
    }

    private fun errorRow(entry: ResolvedProblem, kind: String, verdict: String, ref: Reference, t: Throwable): ParityRow {
        if (System.getProperty("klause.bench.parity.trace")?.toBoolean() == true) {
            System.err.println(">>> $verdict on ${entry.name}:")
            t.printStackTrace()
        }
        return ParityRow(
            entry.name, kind, verdict, "-", "-", ref.name, entry.ref.expected.toString(),
            t.message?.take(160) ?: t::class.simpleName ?: "error",
        )
    }
}
