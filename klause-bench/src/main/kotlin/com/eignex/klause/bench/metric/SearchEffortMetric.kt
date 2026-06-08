package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.Vsids
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Locale

/**
 * Complete-search effort per problem, run as an A/B between two [BacktrackSolver]
 * configurations under a fixed seed and per-instance timeout: a **baseline** CDCL config
 * (VSIDS + phase saving + Luby + LBD) and the **SAT-optimized** preset
 * ([BacktrackPresets.satOptimized] — adaptive restarts, target phasing, three-tier learned DB,
 * binary-resolution minimization, vivification). For each it reports the engine's own
 * [com.eignex.klause.solver.SolveStats] — nodes, conflicts (fails), learned clauses, restarts —
 * plus the verdict and wall time.
 *
 * The conflict count is the search-size signal: a stronger SAT configuration should close the
 * same instances in fewer conflicts. The summary compares total fails over the instances both
 * configs solved, so the SAT-optimized stack's effect is read off directly.
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
internal data class SearchEffortPair(
    val name: String,
    val baseline: SearchEffortReport,
    val satOpt: SearchEffortReport,
)

@Serializable
internal data class SearchEffortResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val seed: Long,
    val timeoutMillis: Long,
    val baselineSolved: Int,
    val satOptSolved: Int,
    val total: Int,
    val bothSolved: Int,
    val baselineFailsSumBothSolved: Long,
    val satOptFailsSumBothSolved: Long,
    val entries: List<SearchEffortPair>,
)

internal object SearchEffortMetric {
    fun run(entries: List<ResolvedProblem>, budget: Budget) {
        val seed = System.getProperty("klause.bench.search.seed")?.toLongOrNull() ?: 1L
        println()
        println(
            "=== search-effort A/B (baseline VSIDS+phase+Luby vs SAT-optimized preset, " +
                "seed=$seed, ${budget.timeoutMillis}ms/instance) ===",
        )
        println(
            "%-20s %18s %18s %9s %9s".format(
                Locale.ROOT,
                "instance",
                "base verdict/fails",
                "sat verdict/fails",
                "base ms",
                "sat ms",
            ),
        )
        val pairs = mutableListOf<SearchEffortPair>()
        for (e in entries) {
            val baseline = solveWith(e, budget) { deadline ->
                BacktrackParams(
                    randomSeed = seed,
                    variableHeuristic = Vsids(),
                    phaseSaving = true,
                    lubyRestartBase = 100L,
                    maxLearnedClauses = 20_000,
                    cancellation = Cancellation { System.currentTimeMillis() > deadline },
                )
            }
            val satOpt = solveWith(e, budget) { deadline ->
                BacktrackPresets.satOptimized(
                    randomSeed = seed,
                    cancellation = Cancellation { System.currentTimeMillis() > deadline },
                )
            }
            pairs += SearchEffortPair(e.name, baseline, satOpt)
            println(
                "%-20s %10s/%-7d %10s/%-7d %9d %9d".format(
                    Locale.ROOT,
                    e.name.take(20),
                    baseline.verdict.take(10),
                    baseline.fails,
                    satOpt.verdict.take(10),
                    satOpt.fails,
                    baseline.wallMs,
                    satOpt.wallMs,
                ),
            )
        }
        val both = pairs.filter { it.baseline.solved && it.satOpt.solved }
        val baseSum = both.sumOf { it.baseline.fails }
        val satSum = both.sumOf { it.satOpt.fails }
        val baseSolved = pairs.count { it.baseline.solved }
        val satSolved = pairs.count { it.satOpt.solved }
        println(
            "--- solved: baseline $baseSolved/${pairs.size}, sat-opt $satSolved/${pairs.size}  |  " +
                "fails over both-solved (${both.size}): baseline=$baseSum sat-opt=$satSum" +
                (if (baseSum > 0) " (%.2fx)".format(Locale.ROOT, satSum.toDouble() / baseSum) else "") + " ---",
        )
        Reports.writeJson(
            "build/bench-search.json",
            SearchEffortResults(
                timestamp = Instant.now().toString(),
                gitSha = Reports.readGitSha(),
                env = EnvInfo.capture(),
                seed = seed,
                timeoutMillis = budget.timeoutMillis,
                baselineSolved = baseSolved,
                satOptSolved = satSolved,
                total = pairs.size,
                bothSolved = both.size,
                baselineFailsSumBothSolved = baseSum,
                satOptFailsSumBothSolved = satSum,
                entries = pairs,
            ),
        )
    }

    /** Run one config over [e] under a fresh per-instance deadline, capturing its effort stats. */
    private fun solveWith(
        e: ResolvedProblem,
        budget: Budget,
        params: (deadline: Long) -> BacktrackParams,
    ): SearchEffortReport {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        val start = System.currentTimeMillis()
        val result = runCatching { BacktrackSolver(e.problem).solve(params(deadline)) }.getOrNull()
        val ms = System.currentTimeMillis() - start
        val st = result?.stats
        return SearchEffortReport(
            name = e.name,
            verdict = result?.let { it::class.simpleName ?: "?" } ?: "ERROR",
            solved = result is SolveResult.Sat || result is SolveResult.Unsat,
            nodes = (st?.nodes?.sum ?: 0.0).toLong(),
            fails = (st?.fails?.sum ?: 0.0).toLong(),
            learned = (st?.learnedClauses?.sum ?: 0.0).toLong(),
            restarts = (st?.restarts?.sum ?: 0.0).toLong(),
            wallMs = ms,
            timedOut = st?.timedOut ?: false,
        )
    }
}
