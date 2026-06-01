package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Anytime optimization comparison: run klause's local-search engine and an in-process
 * [Reference] (OR-Tools by default, or Choco) on each optimization instance under the same
 * wall-clock budget, recording time-to-first-incumbent and best objective reached. Replaces
 * the legacy `LsBench`'s klause-LS-vs-Yuck comparison (Yuck is unavailable as a JVM
 * dependency). Only optimization entries (those with an [Objective]) participate. Override the
 * reference with `-Dklause.bench.anytime.reference=choco|ortools`.
 */
@Serializable
data class AnytimeRow(
    val name: String,
    val klauseFirstMs: Long,
    val klauseBest: Double?,
    val referenceSolver: String,
    val referenceFirstMs: Long,
    val referenceBest: Double?,
    val referenceProvedOptimal: Boolean,
    /** klauseBest - referenceBest (minimization; negative = klause better, positive = worse). */
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
    fun run(entries: List<ResolvedProblem>, budget: Budget = Budget(), reference: Backend = Backend.ORTOOLS) {
        val ref = System.getProperty("klause.bench.anytime.reference")?.let { Reference.byId(it) } ?: Reference.of(reference)
        val opt = entries.filter { it.objective != null }
        println()
        println("=== anytime (klause-LS vs ${ref.name} reference; ${budget.timeoutMillis}ms budget; minimization) ===")
        if (opt.isEmpty()) { println("(no optimization instances in this corpus)"); return }
        val rows = opt.map { row(it, budget, ref) }
        for (r in rows) {
            println("[${r.name}] klause first=${r.klauseFirstMs}ms best=${fmt(r.klauseBest)} | " +
                "${r.referenceSolver} first=${r.referenceFirstMs}ms best=${fmt(r.referenceBest)}${if (r.referenceProvedOptimal) "*" else ""} | " +
                "gap=${fmt(r.gap)}")
        }
        Reports.writeJson("build/anytime-report.json",
            AnytimeResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), budget.timeoutMillis, rows))
    }

    private fun row(entry: ResolvedProblem, budget: Budget, ref: Reference): AnytimeRow {
        val obj = entry.objective!!
        val (kFirst, kBest, _) = anytime { LocalSearchSolver(entry.problem).improvements(obj, lsParams(budget)) }
        val (rFirst, rBest, rOpt) = anytime { ref.improvements(entry.problem, obj, budget) }
        val gap = if (kBest != null && rBest != null) kBest - rBest else null
        return AnytimeRow(entry.name, kFirst, kBest, ref.name, rFirst, rBest, rOpt, gap)
    }

    /** Drive an anytime [MinimizeResult] stream, timing the first incumbent and capturing the
     *  best objective + whether optimality was proven. */
    private fun anytime(stream: () -> Sequence<MinimizeResult>): Triple<Long, Double?, Boolean> {
        val t0 = System.currentTimeMillis()
        var firstMs = -1L
        var best: Double? = null
        var provedOptimal = false
        for (r in stream()) {
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
