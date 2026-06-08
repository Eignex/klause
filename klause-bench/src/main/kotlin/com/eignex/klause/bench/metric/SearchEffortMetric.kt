package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.Vsids
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Complete-search effort per problem: run [BacktrackSolver] under a fixed, deterministic CDCL
 * configuration and report the engine's own [com.eignex.klause.solver.SolveStats] — nodes,
 * conflicts (fails), learned clauses, restarts — plus the verdict and wall time. Unlike the
 * time metric (wall-clock, multi-backend) this exposes the search-size counters, which are the
 * signal for clause-learning / explanation quality: a sharper conflict explanation should learn
 * more reusable clauses and close the same instance in fewer conflicts.
 *
 * The whole point is A/B-ability: hold this metric and its suite fixed, change one thing in the
 * engine (e.g. an AllDifferent explanation scope), and compare conflicts/solve-rate. The fixed
 * seed makes a single run deterministic; aggregate over a suite of related instances to see past
 * the heavy tail of any one instance.
 *
 * Knobs: `-Dklause.bench.search.seed` (default 1).
 */
@Serializable
data class SearchEffortReport(
    internal val name: String,
    internal val verdict: String,
    internal val solved: Boolean,
    internal val nodes: Long,
    internal val fails: Long,
    internal val learned: Long,
    internal val restarts: Long,
    internal val wallMs: Long,
    internal val timedOut: Boolean,
)

@Serializable
internal data class SearchEffortResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val seed: Long,
    val timeoutMillis: Long,
    val solvedCount: Int,
    val total: Int,
    val solvedFailsSum: Long,
    val solvedFailsMedian: Long,
    val entries: List<SearchEffortReport>,
)

internal object SearchEffortMetric {
    fun run(entries: List<ResolvedProblem>, budget: Budget) {
        val seed = System.getProperty("klause.bench.search.seed")?.toLongOrNull() ?: 1L
        println()
        println(
            "=== search-effort (KLAUSE_COMPLETE, VSIDS+phaseSaving+Luby+LBD, seed=$seed, " +
                "${budget.timeoutMillis}ms/instance) ===",
        )
        println(
            "%-22s %-8s %10s %12s %10s %9s %8s".format(
                "instance",
                "verdict",
                "nodes",
                "fails",
                "learned",
                "restarts",
                "ms",
            ),
        )
        val reports = mutableListOf<SearchEffortReport>()
        for (e in entries) {
            val deadline = System.currentTimeMillis() + budget.timeoutMillis
            val params = BacktrackParams(
                randomSeed = seed,
                variableHeuristic = Vsids(),
                phaseSaving = true,
                lubyRestartBase = 100L,
                maxLearnedClauses = 20_000,
                cancellation = Cancellation { System.currentTimeMillis() > deadline },
            )
            val start = System.currentTimeMillis()
            val result = runCatching { BacktrackSolver(e.problem).solve(params) }.getOrNull()
            val ms = System.currentTimeMillis() - start
            val st = result?.stats
            val verdict = result?.let { it::class.simpleName ?: "?" } ?: "ERROR"
            val r = SearchEffortReport(
                name = e.name,
                verdict = verdict,
                solved = result is SolveResult.Sat || result is SolveResult.Unsat,
                nodes = (st?.nodes?.sum ?: 0.0).toLong(),
                fails = (st?.fails?.sum ?: 0.0).toLong(),
                learned = (st?.learnedClauses?.sum ?: 0.0).toLong(),
                restarts = (st?.restarts?.sum ?: 0.0).toLong(),
                wallMs = ms,
                timedOut = st?.timedOut ?: false,
            )
            reports += r
            println(
                "%-22s %-8s %10d %12d %10d %9d %8d".format(
                    r.name.take(22),
                    r.verdict,
                    r.nodes,
                    r.fails,
                    r.learned,
                    r.restarts,
                    r.wallMs,
                ),
            )
        }
        val solved = reports.filter { it.solved }
        val solvedFails = solved.map { it.fails }.sorted()
        val median = if (solvedFails.isEmpty()) 0L else solvedFails[solvedFails.size / 2]
        val sum = solvedFails.sum()
        println("--- solved ${solved.size}/${reports.size}  |  fails over solved: sum=$sum median=$median ---")
        Reports.writeJson(
            "build/bench-search.json",
            SearchEffortResults(
                timestamp = Instant.now().toString(),
                gitSha = Reports.readGitSha(),
                env = EnvInfo.capture(),
                seed = seed,
                timeoutMillis = budget.timeoutMillis,
                solvedCount = solved.size,
                total = reports.size,
                solvedFailsSum = sum,
                solvedFailsMedian = median,
                entries = reports,
            ),
        )
    }
}
