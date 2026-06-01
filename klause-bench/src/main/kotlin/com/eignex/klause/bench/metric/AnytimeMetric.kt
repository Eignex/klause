package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.ortools.OrToolsParams
import com.eignex.klause.ortools.OrToolsSolver
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Anytime optimization comparison: run klause's local-search engine and the OR-Tools CP-SAT
 * **reference** on each optimization instance under the same wall-clock budget, recording
 * time-to-first-incumbent and best objective reached. Replaces the legacy `LsBench`'s
 * klause-LS-vs-Yuck comparison (Yuck is unavailable as a JVM dependency) with an in-process
 * OR-Tools baseline. Only optimization entries (those with an [Objective]) participate.
 */
@Serializable
data class AnytimeRow(
    val name: String,
    val klauseFirstMs: Long,
    val klauseBest: Double?,
    val ortoolsFirstMs: Long,
    val ortoolsBest: Double?,
    val ortoolsProvedOptimal: Boolean,
    /** klauseBest - ortoolsBest (minimization; negative = klause better, positive = worse). */
    val gap: Double?,
)

@Serializable
data class AnytimeResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val budgetMillis: Long,
    val rows: List<AnytimeRow>,
)

object AnytimeMetric {
    fun run(entries: List<ResolvedProblem>, budget: Budget = Budget()) {
        val opt = entries.filter { it.objective != null }
        println()
        println("=== anytime (klause-LS vs OR-Tools reference; ${budget.timeoutMillis}ms budget; minimization) ===")
        if (opt.isEmpty()) { println("(no optimization instances in this corpus)"); return }
        val rows = opt.map { row(it, budget) }
        for (r in rows) {
            println("[${r.name}] klause first=${r.klauseFirstMs}ms best=${fmt(r.klauseBest)} | " +
                "ortools first=${r.ortoolsFirstMs}ms best=${fmt(r.ortoolsBest)}${if (r.ortoolsProvedOptimal) "*" else ""} | " +
                "gap=${fmt(r.gap)}")
        }
        Reports.writeJson("build/anytime-report.json",
            AnytimeResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), budget.timeoutMillis, rows))
    }

    private fun row(entry: ResolvedProblem, budget: Budget): AnytimeRow {
        val obj = entry.objective!!
        val (kFirst, kBest, _) = anytime(LocalSearchSolver(entry.problem), obj, lsParams(budget))
        val (oFirst, oBest, oOpt) = anytime(OrToolsSolver(entry.problem), obj, OrToolsParams(timeoutMillis = budget.timeoutMillis))
        val gap = if (kBest != null && oBest != null) kBest - oBest else null
        return AnytimeRow(entry.name, kFirst, kBest, oFirst, oBest, oOpt, gap)
    }

    /** Drive an optimizer's anytime [Optimizer.improvements] stream, timing the first
     *  incumbent and capturing the best objective + whether optimality was proven. */
    private fun <P : SolverParams> anytime(solver: Optimizer<P>, obj: Objective, params: P): Triple<Long, Double?, Boolean> {
        val t0 = System.currentTimeMillis()
        var firstMs = -1L
        var best: Double? = null
        var provedOptimal = false
        for (r in solver.improvements(obj, params)) {
            when (r) {
                is MinimizeResult.WithSample -> {
                    if (firstMs < 0) firstMs = System.currentTimeMillis() - t0
                    best = r.objective
                    if (r is MinimizeResult.Optimal) provedOptimal = true
                }
                else -> Unit
            }
        }
        return Triple(if (firstMs < 0) -1L else firstMs, best, provedOptimal)
    }

    private fun lsParams(budget: Budget): LocalSearchParams {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        return LocalSearchParams(maxFlips = Long.MAX_VALUE, randomSeed = 1L)
            .withCancellation(Cancellation { System.currentTimeMillis() > deadline }) as LocalSearchParams
    }

    private fun fmt(v: Double?): String = v?.let { "%.1f".format(it) } ?: "—"
}
